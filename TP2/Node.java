import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.*;
import java.util.*;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import javax.swing.Timer;

public class Node {

    protected InetAddress ip; // ip de cada nodo

    // Tabelas de Encaminhamento e custos
    protected Map<InetAddress,String> tabela_encaminhamento = new HashMap<>(); // (ip,estado)
    protected Map<InetAddress,Integer> tabela_custos = new HashMap<>(); // (ip,custo)
    protected InetAddress nodo_anterior = null;

    // Sockects
    protected DatagramSocket socketPing;
    protected DatagramSocket socketPingRouter;
    protected DatagramSocket socketOverlay;
    protected DatagramSocket socketFlooding;
    protected DatagramSocket socketAtivacao;


    //Ping
    protected Map<InetAddress,Integer> tabela_ping = new HashMap<>();
    // Lock
    protected ReentrantLock lock_ping = new ReentrantLock();
    protected Condition condition_ping = lock_ping.newCondition();
    protected Map<InetAddress,Integer> tabela_ping_router = new HashMap<>();
    // Lock
    protected ReentrantLock lock_ping_router = new ReentrantLock();
    protected Condition condition_ping_router = lock_ping_router.newCondition();



    // STream
    DatagramPacket rcvdp; //UDP packet received from the server (to receive)
    DatagramSocket RTPsocket; // socket para enviar e receber pacotes udp
    static int RTP_RCV_PORT = 25000; //port where the client will receive the RTP packets
    Timer cTimer;
    byte[] cBuffer;

    JLabel iconLabel = new JLabel();
    ImageIcon icon;

    public Node() {
        // tem que ter construtor para conseguir fazer construtor cliente
    }

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

        this.socketFlooding = new DatagramSocket(1500,this.ip);
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
                            DatagramPacket pacote_resposta = new DatagramPacket(dados_resposta,dados_resposta.length,inet,1500);
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
                                 DatagramPacket pacote_resposta = new DatagramPacket(dados_resposta1,dados_resposta1.length,ip,1500);
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
        byte[] data_recebido = new byte[1024];
        new Ping(3,tabela_ping,lock_ping,condition_ping);

        DatagramPacket packet_recebido = new DatagramPacket(data_recebido,data_recebido.length); // recebe packet a dizer qual o custo

        // thread que escuta clientes que morreram
        new Thread(() -> {
            try {
                lock_ping.lock();
                while (true){
                    while (!tabela_ping.containsValue(-1)){
                        condition_ping.await();
                    }

                    InetAddress ip_morto = null;
                    for (InetAddress ip : tabela_ping.keySet()){
                        if (tabela_ping.get(ip) == -1 ) ip_morto = ip;
                    }
                    tabela_ping.remove(ip_morto);

                    tabela_encaminhamento.put(ip_morto,"DESATIVADO");
                    System.out.println("Cliente " + ip_morto + " foi desativado");
                }
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            } finally {
                lock_ping.unlock();
            }
        }).start();

        // escutar pings
        while (true) {
            try {
                socketPing.receive(packet_recebido);
                socketPing.setSoTimeout(5000);

                InetAddress ip_recebido = packet_recebido.getAddress();
                System.out.println("Recebi ping do " + ip_recebido);

                try {
                    lock_ping.lock();

                    // se o ip recebido ja ta na tabela vou aumentar 1 o nº de pings
                    if (tabela_ping.containsKey(ip_recebido)) {
                        int nping = tabela_ping.get(ip_recebido);
                        tabela_ping.put(ip_recebido,nping + 1);
                    }else { //se o ip nao ta na tabela tenho de meter o ip na tabela com o numero max de pings que esta na tabela pq se metessemos
                        // 1 e o max tivesse por exemplo 5  este ia ja tar morto pq nos definimos que todos os nodos que tenham menos de max - 3 estavam mortos

                        int max = 1;
                        for (InetAddress i : tabela_ping.keySet()){
                            if (tabela_ping.get(i) > max)
                                max = tabela_ping.get(i);
                        }
                        tabela_ping.put(ip_recebido,max);
                    }
                } finally {
                    lock_ping.unlock();
                }

            } catch (SocketTimeoutException e){
                // se levar timeout

                //meter todas as suas routas desativadas
                for (InetAddress ip : tabela_encaminhamento.keySet()){
                    tabela_encaminhamento.put(ip,"DESATIVADO");
                }


                socketPing.setSoTimeout(0);

                // envia o no anterior que desativou a sua conexao
                Packet p = new Packet(5,0,null);
                byte[] data = p.serialize();
                DatagramPacket dpacket = new DatagramPacket(data, data.length,nodo_anterior,2000);
                socketAtivacao.send(dpacket);
            }
        }
    }


    public void pingRouter() throws IOException,InterruptedException{
        byte[] data_recebido = new byte[1024];
        new Ping(3,tabela_ping_router,lock_ping_router,condition_ping_router);

        DatagramPacket packet_recebido = new DatagramPacket(data_recebido,data_recebido.length); // recebe packet a dizer qual o custo

        // thread que escuta routers que morreram
        new Thread(() -> {
            try {
                lock_ping_router.lock();

                while (true){
                    while (!tabela_ping_router.containsValue(-1)){
                        condition_ping_router.await();
                    }

                    InetAddress ip_morto = null;
                    for (InetAddress ip : tabela_ping_router.keySet()){
                        if (tabela_ping_router.get(ip) == -1 ) ip_morto = ip;
                    }
                    tabela_ping_router.remove(ip_morto);

                    tabela_encaminhamento.put(ip_morto,"DESATIVADO");
                    System.out.println("Router " + ip_morto + " foi desativado");

                    tabela_custos.remove(ip_morto);

                    if (nodo_anterior.equals(ip_morto)){
                        InetAddress ip_min = null;
                        for (InetAddress i : tabela_custos.keySet()){
                            if (ip_min == null || tabela_custos.get(i) < tabela_custos.get(ip_min) )
                                ip_min = i;
                        }

                        nodo_anterior = ip_min;

                        if (tabela_encaminhamento.containsValue("ATIVADO")){
                            // envia mensagem de ativacao caso possua alguma rota ativa
                            Packet p = new Packet(3,0,null);
                            byte[] data = p.serialize();
                            DatagramPacket packet = new DatagramPacket(data, data.length,nodo_anterior,2000);
                            socketAtivacao.send(packet);
                        }
                    }
                    System.out.println("Router " + ip_morto + "foi removido");
                }
            } catch (InterruptedException | IOException e) {
                e.printStackTrace();
            } finally {
                lock_ping_router.unlock();
            }
        }).start();

        // escutar pings
        while (true) {
            try {
                socketPingRouter.receive(packet_recebido);
                socketPingRouter.setSoTimeout(5000);

                InetAddress ip_recebido = packet_recebido.getAddress();
                //System.out.println("Recebi ping do " + ip_recebido);

                try {
                    lock_ping_router.lock();

                    // se o ip recebido ja ta na tabela vou aumentar 1 o nº de pings
                    if (tabela_ping_router.containsKey(ip_recebido)) {
                        int nping = tabela_ping_router.get(ip_recebido);
                        tabela_ping_router.put(ip_recebido,nping + 1);
                    }else { //se o ip nao ta na tabela tenho de meter o ip na tabela com o numero max de pings que esta na tabela pq se metessemos
                        // 1 e o max tivesse por exemplo 5  este ia ja tar morto pq nos definimos que todos os nodos que tenham menos de max - 3 estavam mortos

                        int max = 1;
                        for (InetAddress i : tabela_ping_router.keySet()){
                            if (tabela_ping_router.get(i) > max)
                                max = tabela_ping_router.get(i);
                        }
                        tabela_ping_router.put(ip_recebido,max);
                    }
                } finally {
                    lock_ping_router.unlock();
                }

            } catch (SocketTimeoutException e){
                System.out.println("Todos os nodos ligados ao router " + ip + "foram desativados");
            }
        }

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
        //init para a parte do cliente
        //--------------------------

        cTimer = new Timer(20, new clientTimerListener());
        cTimer.setInitialDelay(0);
        cTimer.setCoalesce(true);
        cBuffer = new byte[15000]; //allocate enough memory for the buffer used to receive data from the server

        cTimer.start();
        try {
            // socket e video
            RTPsocket = new DatagramSocket(RTP_RCV_PORT); //init RTP socket (o mesmo para o cliente e servidor)
            RTPsocket.setSoTimeout(5000); // setimeout to 5s

        } catch (SocketException e) {
            System.out.println("Cliente: erro no socket: " + e.getMessage());
        }
    }
    
    class clientTimerListener implements ActionListener {
        public void actionPerformed(ActionEvent e) {

            //Construct a DatagramPacket to receive data from the UDP socket
            rcvdp = new DatagramPacket(cBuffer, cBuffer.length);

            try{
                //receive the DP from the socket:
                RTPsocket.receive(rcvdp);

                //create an RTPpacket object from the DP
                RTPpacket rtp_packet = new RTPpacket(rcvdp.getData(), rcvdp.getLength());

                //print important header fields of the RTP packet received:
                System.out.println("Got RTP packet with SeqNum # "+rtp_packet.getsequencenumber()+" TimeStamp "+rtp_packet.gettimestamp()+" ms, of type "+rtp_packet.getpayloadtype());

                //print header bitstream:
                rtp_packet.printheader();

                //get the payload bitstream from the RTPpacket object
                int payload_length = rtp_packet.getpayload_length();
                byte [] payload = new byte[payload_length];
                rtp_packet.getpayload(payload);

                //get an Image object from the payload bitstream
                Toolkit toolkit = Toolkit.getDefaultToolkit();
                Image image = toolkit.createImage(payload, 0, payload_length);

                //display the image as an ImageIcon object
                icon = new ImageIcon(image);
                iconLabel.setIcon(icon);
            }
            catch (InterruptedIOException iioe){
                System.out.println("Nothing to read");
            }
            catch (IOException ioe) {
                System.out.println("Exception caught: "+ioe);
            }
        }
    }



}









