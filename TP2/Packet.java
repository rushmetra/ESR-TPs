import java.io.*;
import java.net.InetAddress;
import java.util.List;

public class Packet implements Serializable {
    private int tipo; // identifica o tipo da mensagem:
    // tipo 0 : criar overlay | tipo 1 : atualizar overlay | tipo 2: flooding | tipo 3: activate
    // tipo 4 : ping cliente | tipo 5: ping cliente morreu | tipo 6: ping router | tipo 7 : resposta serv vizinhos
    // tipo 8 : SYN | tipo 9: ACK
    private int dados;
    private List<InetAddress> vizinhos; // vizinhos de um determinado nodo

    public Packet(byte[] recBytes) {

        try {
            Packet msg = deserialize(recBytes);
            this.dados = msg.getDados();
            this.tipo = msg.getTipo();
            this.vizinhos = msg.getVizinhos();
        } catch (IOException | ClassNotFoundException e) {
            e.printStackTrace();
        }
    }

    public Packet(int tipo, int dados, List<InetAddress> vizinhos) {
        this.tipo = tipo;
        this.dados = dados;
        this.vizinhos = vizinhos;
    }

    public List<InetAddress> getVizinhos() {
        return vizinhos;
    }

    public int getTipo() {
        return tipo;
    }

    public int getDados() {
        return dados;
    }

    byte[] serialize() throws IOException {

        ByteArrayOutputStream bStream = new ByteArrayOutputStream();
        ObjectOutput oo = new ObjectOutputStream(bStream);
        oo.writeObject(this);
        oo.close();
        byte[] serializedMessage = bStream.toByteArray();
        return serializedMessage;
    }

    public Packet deserialize(byte[] recBytes) throws IOException, ClassNotFoundException {
        ObjectInputStream iStream = new ObjectInputStream(new ByteArrayInputStream(recBytes));
        Packet messageClass = (Packet) iStream.readObject();
        iStream.close();

        return messageClass;
    }
}