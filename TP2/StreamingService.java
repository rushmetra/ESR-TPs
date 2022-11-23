import java.io.IOException;
import java.net.InetAddress;
import java.util.Map;

public class StreamingService {
    public static void main(String args[]) throws IOException {

        String opcao = args[0];

        if (opcao.compareTo("S") == 0) { // Flag que indica Servidor
            System.out.println("-------- SERVIDOR --------");

            Servidor s = new Servidor(); // criação do servidor

        } else if (opcao.compareTo("C") == 0) { // Flag que indica Cliente
            String ipServer = args[1];
            System.out.println("-------- CLIENTE --------");

            Cliente c = new Cliente(InetAddress.getByName(ipServer)); // criação do cliente

        } else { // Caso não haja uma flag apenas corre um nodo overlay

            System.out.println("-------- NODO --------");
            String ipServer = args[0];
            try {
                System.out.println(InetAddress.getByName(ipServer));
                Node n = new Node(InetAddress.getByName(ipServer));
            } catch (ClassNotFoundException e) {
                e.printStackTrace();
            }
        }
    }
}
