import java.io.IOException;
import java.net.*;
import java.util.*;

public class Node {

    private InetAddress ip; // ip de cada nodo

    // Tabelas de Encaminhamento e custos
    private Map<InetAddress,String> tabela_encaminhamento = new HashMap<>(); // (ip,estado)
    private Map<InetAddress,Integer> tabela_custos = new HashMap<>(); // (ip,custo)
    private InetAddress nodo_anterior = null;

    // Sockects
    private DatagramSocket socketPing;
    private DatagramSocket socketPingRouter;
    private DatagramSocket socketOverlay;
    private DatagramSocket socketFlooding;
    private DatagramSocket socketAtivacao;


    //Ping
    private Map<InetAddress,Integer> tabela_ping = new HashMap<>();
    private Map<InetAddress,Integer> tabela_ping_router = new HashMap<>();


    // STream
    DatagramPacket recebido;
    DatagramSocket RTPsocket; // enviar e receber pacotes udp
    static int porta_rtp = 20000;


    Timer temporizador;
    byte[] cont_buffer;

    //public Node() {}

    public Node(InetAddress ipServer) throws IOException,ClassNotFoundException {
        InetAddress ip_address = null;

        // ir buscar ipv4 do nodo em que está
        Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
        for(NetworkInterface netint : Collections.list(interfaces)) {
            if (!netint.isLoopback() || !netint.isUp()) {
                Enumeration<InetAddress> inetAddresses = netint.getInetAddresses();
                for(InetAddress inetA : Collections.list(inetAddresses)) {
                    if (inetA instanceof Inet4Address)
                        ip_address = inetA;
                }
            }
        }

        System.out.println("Ip adress escolhido: " + ip_address);

        this.ip = ip_address;

        this.socketFlooding = new DatagramSocket(1000,this.ip);
        this.socketAtivacao = new DatagramSocket(2000,this.ip);
        this.socketOverlay = new DatagramSocket(3000,this.ip);
        this.socketPing = new DatagramSocket(4000,this.ip);
        this.socketPingRouter = new DatagramSocket(5000,this.ip);

        new Thread(() -> { // thread para criar a rede overlay
            try {
                createOverlay(ipServer);
            } catch (IOException | ClassNotFoundException | InterruptedException e) {
                e.printStackTrace();
            }
        }).start();

        new Thread(() -> { // thread escuta floods
            try {
                flood();
            } catch (IOException | ClassNotFoundException | InterruptedException e) {
                e.printStackTrace();
            }
        }).start();

        new Thread(() -> { // thread escuta ativaçoes
            try {
                escuta_ativacao();
            } catch (IOException | ClassNotFoundException | InterruptedException e) {
                e.printStackTrace();
            }
        }).start();


        new Thread(() -> { // thread escuta pings dos clientes
            try {
                ping();
            } catch (IOException | ClassNotFoundException | InterruptedException e) {
                e.printStackTrace();
            }
        }).start();

        new Thread(() -> { // thread escuta pings router
            try {
                pingRouter();
            } catch (IOException | ClassNotFoundException | InterruptedException e) {
                e.printStackTrace();
            }
        }).start();

        new Thread(() -> { // thread que envia ping ao router
            try {
                enviarPingRouter();
            } catch (IOException | ClassNotFoundException | InterruptedException e) {
                e.printStackTrace();
            }
        }).start();

       new Thread(this::stream).start();

    }

    public void createOverlay(InetAddress ipServidor) throws IOException, InterruptedException {
        InetAddress ip = this.ip; // ip do nodo em que esta

        // tipo 0 - criar overlay
        Packet packet = new Packet(0,0,null);

        byte[] respostaData = packet.serialize();

        // envia pedido para o servidor
        DatagramPacket pacoteResposta = new DatagramPacket(respostaData,respostaData.length,ipServidor,3000);
        socketOverlay.send(pacoteResposta);

        byte[] recebidosData = new byte[1024];

        // recebe a resposta do servidor ao pedido
        DatagramPacket pacoteRecebido = new DatagramPacket(recebidosData,recebidosData.length);
        socketOverlay.receive(pacoteRecebido);

        // mete a mensagem recebida no recebidosData
        recebidosData = pacoteRecebido.getData();

        Packet recebido = new Packet(recebidosData);

        for (InetAddress i : recebido.getVizinhos()){
            this.tabela_encaminhamento.put(i,"DESATIVADO");
            System.out.println("adicionado à tabela de encaminhamento o vizinho "+ i.toString());

            // avisa os vizinhos que entrou na topologia
            Packet newPacket = new Packet(1,0,null);
            byte[] newData = newPacket.serialize();

            DatagramPacket novoNode = new DatagramPacket(newData,newData.length,3000);
            socketOverlay.send(novoNode);
            Thread.sleep(50);
            System.out.println("Envia mensagem para todos a avisar que entrou na topologia");
        }

        while (true){

            byte[] dados = new byte[1024];

            System.out.println("Espera atualizaaçao overlay");
            DatagramPacket recebeAtualizacaoOverlay = new DatagramPacket(dados, dados.length);
            socketOverlay.receive(recebeAtualizacaoOverlay);

            dados = recebeAtualizacaoOverlay.getData();
            Packet packetRecebido = new Packet(dados);

            if (packetRecebido.getTipo() == 1) // entrou um nodo novo na topologia (tipo 1 atualizacao overlay)
                tabela_encaminhamento.put(recebeAtualizacaoOverlay.getAddress(),"DESATIVADO");

        }s



    }

    public void flood(){
    }

    public void escuta_ativacao(){
    }

    public void ping(){
    }
    public void pingRouter(){
    }
    public void enviarPingRouter(){
    }

    public void stream () {

    }


}




