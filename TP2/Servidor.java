import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;

import Protocol.Interface;
import Protocol.Pacote;

import javax.swing.Timer;

public class Servidor implements ActionListener {
    private String idNodo; // Nome do nodo

    private ReentrantLock sendLock; // lock para socket de envio de pacotes
    private DatagramSocket sendSocket; // socket para enviar pacotes
    private DatagramSocket rcvSocket; // socket para receber pacotes
    public Map<String,Interface> vizinhos; // Map de vizinhos de um nó


    private Map<String, List<Interface>> overlay;
    private Set<String> clientesATransmitir;
    private Set<String> clientesLigados;
    private VideoStream stream;
    private Timer sTimer;
    static int FRAME_PERIOD = 20;

    boolean servPrinc;

    // Video
    static String nome_video;

    public Servidor(String[] args,boolean servPrinc) {

        Runtime.getRuntime().addShutdownHook(new Thread() {
            public void run() {
                System.out.println("Encerrando conexao...");
                encerra_conexao();
            }
        });


        try {
            // coloca o nome do nodo que recebe como argumento
            idNodo = args[0];

            // coloca toda a informação da overlay lida do ficheiro neste hashmap
            this.overlay = new HashMap<>();
            parseFicheiro(args[1]);


            this.sendLock = new ReentrantLock();
            this.sendSocket = new DatagramSocket();
            this.rcvSocket = new DatagramSocket(9090);

            this.vizinhos = new HashMap<>();
            for (Interface v : overlay.get(this.idNodo)) {
                this.vizinhos.put(v.idNodo, v);
            }


            this.stream = new VideoStream(nome_video);
            clientesATransmitir = new HashSet<>();
            clientesLigados = new HashSet<>();
            sTimer = new Timer(FRAME_PERIOD, this); // init Timer para servidor
            sTimer.setInitialDelay(0);
            sTimer.setCoalesce(true);

            servPrinc = servPrinc;


            start();
        }
        catch (IOException e) {
            System.out.println(e.getMessage());
        } catch (Exception e1) {
            System.out.println(e1.getMessage());
        }
    }

    //------------------------------------
    //main
    //------------------------------------
    public static void main(String[] args) {
        boolean servPrinc;
        if (args[2].equals("P")) servPrinc = true;
        else servPrinc = false;
        //se for indicado o nome do video para ser transmitido
        if (args.length >= 4) {
            nome_video = args[3];
            System.out.println("Servidor: VideoFileName indicado como parametro: " + nome_video);
        } else {
            nome_video = "movie.Mjpeg";
            System.out.println("Servidor: parametro não foi indicado. VideoFileName = " + nome_video);
        }

        File f = new File(nome_video);
        if (f.exists()) {
            //Create a Main object
            Servidor s = new Servidor(args,servPrinc);
        } else
            System.out.println("Ficheiro de video não existe: " + nome_video);
    }



    public void start() throws Exception {
        sTimer.start();
        // coloca uma thread à escuta de receber mensagens

        (new Thread() {
            @Override
            public void run() {
                DatagramPacket packet = new DatagramPacket(new byte[65535], 65535);

                while (true) {
                    try {
                        rcvSocket.receive(packet);
                        byte[] packetData = packet.getData();
                        Pacote p = new Pacote(packet.getAddress(),packetData);
                        System.out.println("Mensagem recebida do tipo " + p.tipoMensagem + " do nodo " + p.origem);

                        trataPacotesRecebidos(p.tipoMensagem,p);
                    }
                    catch (Exception ex) {
                        rcvSocket.close();
                        ex.printStackTrace();
                    }
                }
            }
        }).start();

        (new Thread() {
            @Override
            public void run() {
                while(true) {
                    try {
                        Thread.sleep(5000);
                            if(!clientesLigados.isEmpty()) {
                                for (String c : clientesLigados) {
                                    long startTime = System.currentTimeMillis();
                                    Pacote p = new Pacote(6, idNodo, c, startTime);

                                    enviaTodosVizinhos(p);
                                    System.out.println("Enviei ping periodico para nodo " + p.destino );
                                }
                            }

                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                }
            }
        }).start();
    }



    // Resposta a cada pacote recebido
    public void trataPacotesRecebidos(int messageType, Pacote p) throws Exception {
        switch (messageType) {
            case 0: // Inicio de conexao
                new Thread(() -> {
                    try {
                        enviaVizinhos(p);
                        // atualiza informação da overlay e mete o nodo de quem recebeu a mensagem a ativo(p.origem)
                        ativa_interfaces_vizinhos(p.origem);

                        if (p.origem.charAt(0) == 'C') {
                            clientesLigados.add(p.origem);
                        }

                    } catch (IOException e) {
                        System.out.println(e.getMessage());
                    }

                }).start();
                break;

            case 1: // Mensagem de routing
                new Thread(() -> {
                    try {
                        define_nova_rota(p);
                    } catch (IOException e) {
                        System.out.println(e.getMessage());
                    }

                }).start();
                break;

            case 2: // Mensagem de ativacao da rota
                break;

            case 3: // Pedido de envio de multimedia
                new Thread(() -> {
                    try {
                        trataMensagensMultimedia(p);
                    } catch (Exception e) {
                        System.out.println(e.getMessage());
                    }

                }).start();

                break;

            case 4: // ativacao vizinhos
                new Thread(() -> {
                    try {
                        ativa_interface_vizinho(p.origem);
                        System.out.println("Novo vizinho conectado: " + p.origem);
                    } catch (IOException e) {
                        System.out.println(e.getMessage());
                    }

                }).start();

                break;

            case 5: // Mensagem de ping
                new Thread(() -> {
                    try {
                        enviaMsgPing(p);
                    } catch (IOException | InterruptedException e) {
                        System.out.println(e.getMessage());
                    }

                }).start();

                break;

            case -1: // Fim de conexao
                new Thread(() -> {
                    desativa_interfaces_vizinhos(p.origem);
                    clientesATransmitir.remove(p.origem);
                    clientesLigados.remove(p.origem);
                }).start();

                break;

            default:
                break;
        }
    }


    // Envia os seus vizinhos ao nodo que se conectou
    public void enviaVizinhos(Pacote p) throws IOException {
        List<Interface> vizinhosList = overlay.get(p.origem);
        final ByteArrayOutputStream byteOut = new ByteArrayOutputStream();
        final DataOutputStream dataOut = new DataOutputStream(byteOut);
        dataOut.writeInt(vizinhosList.size());

        for (Interface i : vizinhosList) {
            dataOut.writeUTF(i.idNodo);
            dataOut.writeUTF(i.enderecoIP.getHostAddress());
            dataOut.writeBoolean(i.estado);
        }

        dataOut.close();
        byteOut.flush();
        Pacote pSend = new Pacote(0, idNodo, p.origem, byteOut.toByteArray());
        send(pSend, p.ipNodoAnterior);
    }


    public void ativa_interfaces_vizinhos(String idnode) {
        for (List<Interface> li : overlay.values()) {
            for (Interface i : li) {
                if (i.idNodo.equals(idnode))
                    i.estado = true;
            }
        }
    }



    public void trataMensagensMultimedia(Pacote p) throws Exception {
        switch (p.fileInfo.tipoMensagem) {
            case 0: // play
                System.out.println("Começar a transmitir para "+ p.origem);
                clientesATransmitir.add(p.origem);
                break;
            case 1: // pause
                System.out.println("Parar de transmitir para "+ p.origem);
                clientesATransmitir.remove(p.origem);
                break;
            default:
                break;
        }
    }


    public void enviaMsgPing(Pacote p) throws IOException, InterruptedException {
        byte[] data = "Olá!".getBytes();
        Pacote ping = new Pacote(5, idNodo, p.origem, data);
        enviaTodosVizinhos(ping);
    }


    public void desativa_interfaces_vizinhos(String idnode) {
        System.out.println("Nodo " + idnode + " terminou conexão. ");
        for (List<Interface> li : overlay.values()) {
            for (Interface i : li) {
                if (i.idNodo.equals(idnode))
                    i.estado = false;
            }
        }
    }

    // Resposta a mensagem de routing
    // Ativar a rota com o menor tempo
    public void define_nova_rota(Pacote p) throws IOException {
        //long startTime = System.currentTimeMillis();
        Pacote rt = new Pacote(2, idNodo, p.origem, 0);
        enviaTodosVizinhos(rt);
        System.out.println("Ativando rota para " + p.origem);
    }

    public void encerra_conexao()  {
        Pacote p = new Pacote(-1, idNodo, "");
        enviaTodosVizinhos(p);

    }

    public void send(Pacote p, InetAddress dest) throws IOException {
        byte[] data = p.packetToBytes();
        DatagramPacket dp = new DatagramPacket(data,data.length,dest, 9090);
        sendLock.lock();
        try {
            sendSocket.send(dp);
        }
        finally {
            sendLock.unlock();
        }
    }

    // envia pacote para todos os vizinhos
    public void enviaTodosVizinhos(Pacote p) {
        byte[] data;
        try {
            data = p.packetToBytes();
            for(Interface i : vizinhos.values()) {
                if (i.estado) {
                    DatagramPacket dp = new DatagramPacket(data,data.length,i.enderecoIP, 9090);
                    sendLock.lock();
                    try {
                        sendSocket.send(dp);
                    }
                    finally {
                        sendLock.unlock();
                    }
                }
            }
        } catch (IOException e1) {
            e1.printStackTrace();
        }
    }

    // Ativar interface de um vizinho
    public void ativa_interface_vizinho(String idNode) throws IOException {
        Interface i = vizinhos.get(idNode);
        if (i != null)
            i.estado = true;
    }

    // Desativar interface de um vizinho
    public void desativa_interface_vizinho(String idNode) throws IOException {
        Interface i = vizinhos.get(idNode);
        if(i != null)
            i.estado = false;
    }

    // Parse do ficheiro de configuracao
    public void parseFicheiro(String filename) throws IOException {

        try (BufferedReader reader = new BufferedReader(new FileReader(filename))) {

            String line = reader.readLine();

            while (line != null) {

                String[] parte = line.split(" *: *");
                String[] ips_vizinhos = parte[1].split(" *; *");

                List<Interface> interfaceList = new ArrayList<>();

                // percorre todos os vizinhos de um nodo
                for (String s : ips_vizinhos) {
                    String[] data = s.split(" *, *");
                    InetAddress ipa = InetAddress.getByName(data[1]);
                    Interface i;
                    if (data[0].equals(idNodo))
                        i = new Interface(data[0], ipa, true);
                    else
                        i = new Interface(data[0], ipa, false);
                    interfaceList.add(i);
                }

                this.overlay.put(parte[0], interfaceList);

                line = reader.readLine();
            }
        }

        catch (IOException e) {
            e.printStackTrace();
        }
    }

    //------------------------
    //Handler for timer
    //------------------------
    @Override
    public void actionPerformed(ActionEvent e) {
        byte[] data;
        try {
            data = stream.actionPerformed();
            if(!clientesATransmitir.isEmpty()) {
                Pacote p = new Pacote(idNodo, stream.imagenb, clientesATransmitir, data);
                enviaTodosVizinhos(p);
            }
        } catch (IOException e1) {
            e1.printStackTrace();
        }
    }
}