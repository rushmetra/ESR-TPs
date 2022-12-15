package Protocol;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;

public class Interface {
    public String idNodo; // nome do nodo
    public InetAddress enderecoIP; // ip do nodo
    public boolean estado; /* true -> ativa    false -> inativa */

    public Interface(DataInputStream dados) throws UnknownHostException, IOException{
        this.idNodo = dados.readUTF();
        this.enderecoIP = InetAddress.getByName(dados.readUTF());
        this.estado = dados.readBoolean();
    }

    public Interface(String id,InetAddress ip) {
        this.idNodo = id;
        this.enderecoIP = ip;
        this.estado = false;
    }

    public Interface(String id,InetAddress ip, boolean estado) {
        this.idNodo = id;
        this.enderecoIP = ip;
        this.estado = estado;
    }


    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(this.estado);
        //sb.append("[ ").append(this.idNodo).append("->").
        //append(" // ").append(this.estado).append(" ]");
        return sb.toString();
    }
}