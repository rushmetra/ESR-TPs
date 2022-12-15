import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.net.*;
import java.util.*;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

import Protocol.Interface;
import Protocol.Pacote;

public abstract class Node {
    public Interface bootstrapper;
    public Interface bootstrapperAlternativo;
    public String idNodo;


    private ReentrantLock sendLock; // lock para socket de envio de pacotes
    private DatagramSocket sendSocket; // socket para enviar pacotes
    private DatagramSocket rcvSocket; // socket para receber pacotes

    public Map<String,Interface> vizinhos; // Map de vizinhos de um nó

    // A correr: Java Client C1 S1 10.0.0.10
    // Cria uma interface com as informações ---->   S1, 10.0.0.10
    public Node(String[] args) throws UnknownHostException, SocketException {
        this.idNodo = args[0];
        this.bootstrapper = new Interface(args[1],InetAddress.getByName(args[2]));
        if (args.length > 3) {
            this.bootstrapperAlternativo = new Interface(args[3],InetAddress.getByName(args[4]));
        } else {
            this.bootstrapperAlternativo = null;
        }

        this.sendLock = new ReentrantLock();
        this.sendSocket = new DatagramSocket();
        this.rcvSocket = new DatagramSocket(9090);
        this.vizinhos = null;
    }

    // Thread principal que inicia conexao e responde a mensagens recebidas
    public void start() throws Exception {
        System.out.println("Iniciando conexao com o bootstrapper. À espera de vizinhos...");
        //protocol.inicia_conexao_nodo(idNodo, this.bootstrapper);
        Pacote p = new Pacote(0, this.idNodo, this.bootstrapper.idNodo);
        send(p, bootstrapper.enderecoIP);
        if (bootstrapperAlternativo != null) {
            Pacote pA = new Pacote(0, this.idNodo, this.bootstrapperAlternativo.idNodo);
            send(pA, bootstrapperAlternativo.enderecoIP);
        }



        // coloca uma thread à escuta de mensagens
        (new Thread() {
            @Override
            public void run() {
                DatagramPacket packet = new DatagramPacket(new byte[65535], 65535);

                while (true) {
                    try {
                        rcvSocket.receive(packet);
                        byte[] packetData = packet.getData();
                        Pacote p = new Pacote(packet.getAddress(),packetData);

                        trataMensagensRecebidas(p.tipoMensagem,p);
                        //if (p.tipoMensagem != 3) System.out.println("recebi pacote do tipo " + p.tipoMensagem + " do nodo " + packet.getAddress());
                    }
                    catch (Exception ex) {
                        rcvSocket.close();
                        ex.printStackTrace();
                    }
                }
            }
        }).start();

    }

    public abstract void trataMensagensRecebidas(int messageType, Pacote entry) throws Exception;

    // Recebe os vizinhos do Servidor Bootstrapper
    public void recebeVizinhhos(Pacote p) throws IOException {
        atualiza_vizinhos(p.dados);
        avisa_vizinhos(idNodo);
        System.out.println("Conexão estabelecida.\nLista de vizinhos:\n");
        System.out.println(vizinhos.toString());
    }


    public void send(Pacote p, String dest) throws IOException {
        byte[] data = p.packetToBytes();
        DatagramPacket dp = new DatagramPacket(data,data.length, vizinhos.get(dest).enderecoIP, 9090);
        sendLock.lock();
        try {
            sendSocket.send(dp);
        }
        finally {
            sendLock.unlock();
        }
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

    // envia pacote para todos os vizinhos menos para o vizinho ip (argumento)
    public void enviaTodosVizinhosExceto(Pacote p, InetAddress ip) throws IOException {
        byte[] data = p.packetToBytes();
        for(Interface i : vizinhos.values()) {
            if (i.estado && !ip.equals(i.enderecoIP)) {
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
    }

    public void atualiza_vizinhos(byte[] data) throws IOException {
        this.vizinhos = new HashMap<>();
        final ByteArrayInputStream byteIn = new ByteArrayInputStream(data);
        final DataInputStream dataIn = new DataInputStream(byteIn);
        int length = dataIn.readInt();
        for (int i = 0; i < length; i++) {
            Interface interf = new Interface(dataIn);
            vizinhos.put(interf.idNodo, interf);
        }
        dataIn.close();
        byteIn.close();
    }

    public void avisa_vizinhos(String idNode) throws IOException {
        for (Interface i : vizinhos.values()) {
            if (i.estado == true) {
                Pacote p = new Pacote(4, idNode, i.idNodo);
                send(p, i.idNodo);
            }
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

    public boolean e_vizinho(String idNode) {
        return vizinhos.containsKey(idNode);
    }

    // Obter o ID do nodo vizinho
    public String getIdVizinho(InetAddress ip) {
        String node = null;
        for (Interface i : vizinhos.values()) {
            if (i.enderecoIP.equals(ip)) {
                node = i.idNodo;
                break;
            }
        }
        return node;
    }

    public boolean e_router(String idnode) {
        return idnode.charAt(0) == 'R';
    }

    // Obter os vizinhos q sao Router
    public List<InetAddress> getVizinhosRouter() {
        List<InetAddress> routers = new ArrayList<>();
        for(Interface nodo : vizinhos.values()) {
            if (e_router(nodo.idNodo) && nodo.estado == true)
                routers.add(vizinhos.get(nodo.idNodo).enderecoIP);
        }
        return routers;
    }


}