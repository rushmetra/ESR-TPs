/* ------------------
   Servidor
   usage: java Servidor [Video file]
   adaptado dos originais pela equipa docente de ESR (nenhumas garantias)
   colocar primeiro o cliente a correr, porque este dispara logo
   ---------------------- */

import java.io.*;
import java.net.*;
import java.awt.*;
import java.util.*;
import java.awt.event.*;
import java.util.List;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;
import javax.swing.*;
import javax.swing.Timer;


public class Servidor extends JFrame implements ActionListener {

    private Map<InetAddress, List<InetAddress>> bootstrapper; // estrutura que guarda informaçao do ficheiro bootstrapper
    private List<InetAddress> nodosCruciais;

    private List<InetAddress> nodosRede = new ArrayList<>(); // nodos q fazem parte da rede
    private ReentrantLock lockNodosRede = new ReentrantLock();
    private Condition conditionNodosRede = lockNodosRede.newCondition();


    private InetAddress ip; // identificador de cada nodo
    private Map<InetAddress,String> tabela_encaminhamento = new HashMap<>(); // tabela de encaminhamento de cada nodo (Id nodo => Estado)
    private Map<InetAddress,Integer> tabela_custos = new HashMap<>();
    private InetAddress nodo_anterior = null; // nodo adjacente anterior
    private DatagramSocket socketFlooding;
    private DatagramSocket socketAtivacao;
    private DatagramSocket socketOverlay;

    private ReentrantLock lock_tabela_encaminhamento = new ReentrantLock(); //Lock para proteger o acesso à tabela de routing




    //GUI:
  //----------------
  JLabel label;

  //RTP variables:
  //----------------
  DatagramPacket senddp; //UDP packet containing the video frames (to send)A
  DatagramSocket RTPsocket; //socket to be used to send and receive UDP packet
  int RTP_dest_port = 25000; //destination port for RTP packets 
  InetAddress ClientIPAddr; //Client IP address
  
  static String VideoFileName; //video file to request to the server

  private Thread stream;
  private InetAddress ip_stream;
  private boolean prunning = false;

  //Video constants:
  //------------------
  int imagenb = 0; //image nb of the image currently transmitted
  VideoStream video; //VideoStream object used to access video frames
  static int MJPEG_TYPE = 26; //RTP payload type for MJPEG video
  static int FRAME_PERIOD = 100; //Frame period of the video to stream, in ms
  static int VIDEO_LENGTH = 500; //length of the video in frames

  Timer sTimer; //timer used to send the images at the video frame rate
  byte[] sBuf; //buffer used to store the images to send to the client 

  //--------------------------
  //Constructor
  //--------------------------
    public Servidor() throws IOException {

      parseBootstrapper();
      InetAddress ip = null;

      Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
      for(NetworkInterface i : Collections.list(interfaces)) {
          if (!i.isLoopback() || !i.isUp()) {
              Enumeration<InetAddress> inetAddresses = i.getInetAddresses();

              for(InetAddress iA : Collections.list(inetAddresses) ) {
                  if (iA instanceof Inet4Address)
                      ip = iA;
              }
          }
      }

      this.ip = ip;

      this.socketFlooding = new DatagramSocket(1500, this.ip);
      this.socketAtivacao = new DatagramSocket(2000, this.ip);
      this.socketOverlay = new DatagramSocket(3000, this.ip);

      new Thread(() -> { // thread para criar a rede overlay
          try {
              comecaOverlay();
          } catch (IOException e) {
              e.printStackTrace();
          }
      }).start();

      new Thread(() -> { // thread escuta floods
          try {
              comecaFlood();
          } catch (IOException | InterruptedException e) {
              e.printStackTrace();
          }
      }).start();

      new Thread(() -> { // thread escuta ativaçoes
          try {
              escuta_ativacao();
          } catch (IOException | InterruptedException e) {
              e.printStackTrace();
          }
      }).start();

      stream = new Thread(() -> {
          try {
              stream(this.ip);
          } catch (Exception e) {
              e.printStackTrace();
          }
      });
  }

    public Servidor(InetAddress ip) throws Exception {

        super("Servidor");

        // init para a parte do servidor
        sTimer = new Timer(FRAME_PERIOD, this); //init Timer para servidor
        sTimer.setInitialDelay(0);
        sTimer.setCoalesce(true);
        sBuf = new byte[15000]; //allocate memory for the sending buffer

        try {
            RTPsocket = new DatagramSocket(); //init RTP socket

            ClientIPAddr = ip;

            System.out.println("Servidor: socket " + ClientIPAddr);
            video = new VideoStream(VideoFileName); //init the VideoStream object:
            System.out.println("Servidor: vai enviar video da file " + VideoFileName);

        } catch (SocketException e) {
            System.out.println("Servidor: erro no socket: " + e.getMessage());
        } catch (InterruptedException e) {
            System.out.println("Erro await");
        }

        //Handler to close the main window
        addWindowListener(new WindowAdapter() {
            public void windowClosing(WindowEvent e) {
                //stop the timer and exit
                sTimer.stop();
                System.exit(0);
            }});

        //GUI:
        label = new JLabel("Send frame #        ", JLabel.CENTER);
        getContentPane().add(label, BorderLayout.CENTER);

        sTimer.start();
    }
    public void comecaOverlay() throws IOException {

      boolean signal = false;

      List<InetAddress> vizinhos = bootstrapper.get(this.ip);

      for (InetAddress i : vizinhos){
          tabela_encaminhamento.put(i,"DESATIVADO");
      }

      try{
          lockNodosRede.lock();
          nodosRede.add(this.ip);
      } finally {
          lockNodosRede.unlock();
      }

        while (true) { //server está à escuta de nodos atétodo o bootstrapper ser lido

            System.out.println("Leitura de Nodos na rede de Overlay");

            byte[] data_recebido = new byte[1024];
            DatagramPacket pacote_recebido = new DatagramPacket(data_recebido, data_recebido.length); //fica bloqueado no receive até receber alguma mensagem
            socketOverlay.receive(pacote_recebido);

            parseBootstrapper();

            data_recebido = pacote_recebido.getData(); //Transformar o pacote em bytes

            Packet p = new Packet(data_recebido);
            InetAddress ip_nodo = pacote_recebido.getAddress();

            if (p.getTipo() == 1) { // tipo overlay

                System.out.println("Leu Nodo " + ip_nodo);

                try {
                    lockNodosRede.lock();
                    nodosRede.add(ip_nodo);
                } finally {
                    lockNodosRede.unlock();
                }

                try {
                    lockNodosRede.lock();
                    // quando todos os nodos cruciais estao ativos
                    if (nodosRede.containsAll(nodosCruciais) && !signal) { // signal para dizer que apenas faz o signal uma vez.
                        conditionNodosRede.signalAll(); // Acorda a thread adormecida quando todos os nodos importantes estiverem ativos
                        System.out.println("A Iniciar Flooding");
                        signal = true;
                    }
                } finally {
                    lockNodosRede.unlock();
                }

                List<InetAddress> listaVizinhos = bootstrapper.get(ip_nodo);

                Packet send = new Packet(7,0, listaVizinhos);

                byte[] dataresposta= send.serialize(); // serializa VIZINHOS

                DatagramPacket pacote_resposta = new DatagramPacket(dataresposta, dataresposta.length, ip_nodo, 4321);
                socketOverlay.send(pacote_resposta);
            } else{
                tabela_encaminhamento.put(ip_nodo, "DESATIVADO");
            }
        }
    }

    public void comecaFlood() throws InterruptedException, IOException {
      try {
          lockNodosRede.lock();

          while (!nodosRede.containsAll(nodosCruciais))
              conditionNodosRede.await();

      } finally {
          lockNodosRede.unlock();
      }
      // ja todos os nodos cruciais estao ativos

      while(true){
          Thread.sleep(50);
          System.out.println("Comecou Flood");

          List<InetAddress> vizinhos = new ArrayList<>();
          vizinhos = bootstrapper.get(this.ip);

          for (InetAddress i : vizinhos){
              Packet p = new Packet(2,1,null);

              byte[] data_resposta = p.serialize();
              DatagramPacket pacote_resposta = new DatagramPacket(data_resposta, data_resposta.length,i,1500);
              socketFlooding.send(pacote_resposta);
          }

          // FIXME testar tirar
          Thread.sleep(20000);
      }
    }

    public void escuta_ativacao() throws IOException,InterruptedException {
        byte[] data_custo = new byte[1024];

        DatagramPacket pacote_rcv_custo = new DatagramPacket(data_custo, data_custo.length);

        while (true) {
            System.out.println("comecar a ativacao servidor \n");

            socketAtivacao.receive(pacote_rcv_custo);
            byte[] data = pacote_rcv_custo.getData();
            Packet p = new Packet(data);

            if (p.getTipo() == 3) { // tipo ativacao
                InetAddress address = pacote_rcv_custo.getAddress();

                try {
                    lock_tabela_encaminhamento.lock();

                    if (tabela_encaminhamento.get(address).equals("DESATIVADO")) {
                        tabela_encaminhamento.put(address, "ATIVADO");

                        if(!prunning) {
                            prunning = true;
                            System.out.println("STREAM INICIADA!");
                            this.ip_stream = address;
                            stream.start();

                        }
                    }
                } finally {
                    lock_tabela_encaminhamento.unlock();
                }
                System.out.println("Nodo " + address + "foi ativado");
            } else if (p.getTipo() == 5) { // Mete o nodo que recebeu desativado

                InetAddress adress = pacote_rcv_custo.getAddress();
                tabela_encaminhamento.put(adress, "DESATIVADO");
                System.out.println("O nodo com o ip  " + adress + " foi desativado");

            } else {
                System.out.println("Recebeu uma mensagem do tipo errado que nao foi tipo 3 (Ativacao) e 5 (ping cliente Pruning)");
            }
        }
    }

    public void parseBootstrapper()  {

        String pathBootstrapper = "/Users/ruimoreira/Desktop/ESR-TPs/TP2/src/boostrapper.txt";

        BufferedReader bf = null;
        try {
            bf = new BufferedReader(new FileReader(pathBootstrapper));
        } catch (FileNotFoundException e) {
            throw new RuntimeException(e);
        }
        List<String> linhas = bf.lines().toList();

        bootstrapper = new HashMap<>();
        nodosCruciais = new ArrayList<>();

        for(String n : linhas) {
            String [] param = n.split(":");
            String ip_nodo = param[0];
            String [] ips_vizinhos = param[1].split(",");

            InetAddress inet_nodo;

            if (ip_nodo.contains("!")) {
                ip_nodo = ip_nodo.replace("!","");

                try {
                    inet_nodo = InetAddress.getByName(ip_nodo);
                } catch (UnknownHostException e) {
                    throw new RuntimeException(e);
                }

                nodosCruciais.add(inet_nodo);
            } else {
                try {
                    inet_nodo = InetAddress.getByName(ip_nodo);
                } catch (UnknownHostException e) {
                    throw new RuntimeException(e);
                }
            }

            List<InetAddress> vizinhos = new ArrayList<>();

            for(String s : ips_vizinhos) {
                InetAddress viz = null;
                try {
                    viz = InetAddress.getByName(s);
                } catch (UnknownHostException e) {
                    throw new RuntimeException(e);
                }
                vizinhos.add(viz);
            }
            bootstrapper.put(inet_nodo,vizinhos);
        }
        try {
            bf.close();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }


    public void stream(InetAddress ip) throws Exception {
      VideoFileName = "movie.Mjpeg";
      System.out.println("Video File name " + VideoFileName);

      File f = new File(VideoFileName);
      if (f.exists()){
          Servidor s = new Servidor(ip) ;
      }else {
          System.out.println("Ficheiro de video " + VideoFileName + " não existe.");
      }
    }


  //------------------------
  //Handler for timer
  //------------------------
  public void actionPerformed(ActionEvent e) {

    //if the current image nb is less than the length of the video
    if (imagenb < VIDEO_LENGTH)
      {
	//update current imagenb
	imagenb++;
       
	try {
	  //get next frame to send from the video, as well as its size
	  int image_length = video.getnextframe(sBuf);

	  //Builds an RTPpacket object containing the frame
	  RTPpacket rtp_packet = new RTPpacket(MJPEG_TYPE, imagenb, imagenb*FRAME_PERIOD, sBuf, image_length);
	  
	  //get to total length of the full rtp packet to send
	  int packet_length = rtp_packet.getlength();

	  //retrieve the packet bitstream and store it in an array of bytes
	  byte[] packet_bits = new byte[packet_length];
	  rtp_packet.getpacket(packet_bits);

	  //send the packet as a DatagramPacket over the UDP socket 
	  senddp = new DatagramPacket(packet_bits, packet_length, ClientIPAddr, RTP_dest_port);
	  RTPsocket.send(senddp);

	  System.out.println("Send frame #"+imagenb);
	  //print the header bitstream
	  rtp_packet.printheader();

	  //update GUI
	  //label.setText("Send frame #" + imagenb);
	}
	catch(Exception ex)
	  {
	    System.out.println("Exception caught: "+ex);
	    System.exit(0);
	  }
      }
    else
      {
	//if we have reached the end of the video file, stop the timer
	sTimer.stop();
      }
  }

}//end of Class Servidor
