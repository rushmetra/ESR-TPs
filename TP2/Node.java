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
            } catch (IOException  | InterruptedException e) {
                e.printStackTrace();
            }
        }).start();

        new Thread(() -> { // thread escuta floods
            try {
                flood();
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


        new Thread(() -> { // thread escuta pings dos clientes
            try {
                ping();
            } catch (IOException  | InterruptedException e) {
                e.printStackTrace();
            }
        }).start();

        new Thread(() -> { // thread escuta pings router
            try {
                pingRouter();
            } catch (IOException  | InterruptedException e) {
                e.printStackTrace();
            }
        }).start();

        new Thread(() -> { // thread que envia ping ao router
            try {
                enviarPingRouter();
            } catch (IOException  | InterruptedException e) {
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

        }

    }

    public void flood() throws IOException,InterruptedException {
        byte[] data_rcv_custo = new byte[1024];

        DatagramPacket pacote_rcv_custo = new DatagramPacket(data_rcv_custo,data_rcv_custo.length);

        while (true){
            System.out.println("comecar o flood \n");

            socketFlooding.receive(pacote_rcv_custo);
            // Thread.sleep(50); // FIXME isto estava ativo

            byte[] data = pacote_rcv_custo.getData();
            Packet p = new Packet(data);

            if (p.getTipo() == 2) { // tipo flooding
                InetAddress ip_origem = pacote_rcv_custo.getAddress();

                int custo = p.getDados();

                System.out.println("Recebi do "+ ip_origem + " com custo " + custo);

                if (nodo_anterior == null) {
                    nodo_anterior = ip_origem;
                    tabela_custos.put(ip_origem,custo);

                    System.out.println("1º iteracao: vou enviar para os meus vizinhos com custo "+ custo);

                    for (InetAddress inet : tabela_encaminhamento.keySet()){
                        if (!inet.equals(nodo_anterior)){
                            Packet mensagem = new Packet(2,custo+1,null);
                            byte[] dados_resposta = mensagem.serialize();
                            DatagramPacket pacote_resposta = new DatagramPacket(dados_resposta,dados_resposta.length,inet,1000);
                            socketFlooding.send(pacote_resposta);
                        }
                    }
                } else {
                    int custo_anterior = tabela_custos.get(nodo_anterior);
                    if (custo <= custo_anterior){
                        nodo_anterior = ip_origem;
                         for (InetAddress ip : tabela_encaminhamento.keySet()){
                             if (!ip.equals(nodo_anterior)){
                                 Packet mensagem1 = new Packet(2,custo+1,null);
                                 byte[] dados_resposta1 = mensagem1.serialize();
                                 DatagramPacket pacote_resposta = new DatagramPacket(dados_resposta1,dados_resposta1.length,ip,1000);
                                 socketFlooding.send(pacote_resposta);
                             }
                         }
                    }
                    if (tabela_custos.containsKey(ip_origem)){      // quando tem na tabela de custos
                        int custo_antigo_origem = tabela_custos.get(ip_origem);
                        if (custo_antigo_origem >= custo){
                            tabela_custos.put(ip_origem,custo);
                        }
                    } else { // nao tem na tabela de custos
                        tabela_custos.put(ip_origem,custo);
                    }
                }

            } else {
                System.out.println("Recebeu uma mensagem do tipo errado que nao foi tipo 1 (Flooding)");
            }
        }
    }

    public void escuta_ativacao() throws IOException,InterruptedException {
        byte[] data_custo = new byte[1024];

        DatagramPacket pacote_rcv_custo = new DatagramPacket(data_custo, data_custo.length);

        while (true) {
            System.out.println("comecar a ativacao \n");

            socketAtivacao.receive(pacote_rcv_custo);
            //Thread.sleep(50);

            byte[] data = pacote_rcv_custo.getData();
            Packet p = new Packet(data);

            if (p.getTipo() == 3) { // tipo ativacao
                if (nodo_anterior != null) {
                    InetAddress inet_ip = pacote_rcv_custo.getAddress();
                    tabela_encaminhamento.put(inet_ip, "ATIVADO");

                    System.out.println("O nodo com o ip  " + inet_ip + " foi ativado");

                    Packet packet_msg_ativacao = new Packet(3, 0, null);
                    byte[] resposta = packet_msg_ativacao.serialize();
                    DatagramPacket datagram_resposta = new DatagramPacket(resposta, resposta.length, nodo_anterior, 2000);
                    socketAtivacao.send(datagram_resposta);
                }
            } else if (p.getTipo() == 5) { // Desativacao das rotas que levaram timeout
                InetAddress adress = pacote_rcv_custo.getAddress();
                tabela_encaminhamento.put(adress, "DESATIVADO");
                System.out.println("O nodo com o ip  " + adress + " foi desativado");

                if (!tabela_encaminhamento.containsValue("ATIVADO")) { // nao tem nenhuma rota ativa e pode se desativar a ele propio
                    Packet packet_msg_desativar = new Packet(5, 0, null);

                    byte[] resposta = packet_msg_desativar.serialize();
                    DatagramPacket datagram_desativar = new DatagramPacket(resposta, resposta.length, nodo_anterior, 2000);
                    socketAtivacao.send(datagram_desativar);

                }

            } else {
                System.out.println("Recebeu uma mensagem do tipo errado que nao foi tipo 3 (Ativacao) e 5 (ping cliente Pruning)");
            }
        }
    }

    public void ping() throws IOException,InterruptedException{
        byte[] data = new byte[1024];


    }
    public void pingRouter() throws IOException,InterruptedException{

    }
    public void enviarPingRouter() throws IOException,InterruptedException{
        while (true) {
            Thread.sleep(500); // enquanto o nodo ta vivo vai avisando os adjacentes de 500ms em 500ms

            for(InetAddress ip : tabela_encaminhamento.keySet()){

                Packet p = new Packet(6,0,null);
                byte[] data_resposta = p.serialize();
                DatagramPacket pacote_resposta = new DatagramPacket(data_resposta,data_resposta.length,ip,5000);
                socketPingRouter.send(pacote_resposta);
            }
        }
    }

    public void stream () {

    }



}




