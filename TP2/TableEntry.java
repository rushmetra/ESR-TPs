import java.net.InetAddress;
import java.net.UnknownHostException;

public class TableEntry {
    public String destino;
    public boolean estado; // false - inativa; true - ativa;
    public int nSaltos; // numero de saltos
    public long custoTempo; // custo Tempo
    public InetAddress proxSalto;

    public TableEntry(String destino, InetAddress proximo_salto, int custoS, long custoT) {
        this.destino = destino;
        this.proxSalto = proximo_salto;
        this.estado = false;
        this.nSaltos = custoS;
        this.custoTempo = custoT;
    }

    public TableEntry(String destino, InetAddress proximo_salto, int custoS) {
        this.destino = destino;
        this.proxSalto = proximo_salto;
        this.estado = false;
        this.nSaltos = custoS;
    }

    public TableEntry(String destino, InetAddress proximo, int custoS, boolean estado)  {
        this.destino = destino;
        this.proxSalto = proximo;
        this.estado = estado;
        this.nSaltos = custoS;
    }

    public void ativa_interface() {
        estado = true;
    }



    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("Destino: ").append(this.destino)
                .append("\nEstado: ").append(this.estado)
                .append("\nCusto Saltos: ").append(this.nSaltos)
                .append("\nProximo salto: ").append(this.proxSalto).append("\n");

        return sb.toString();
    }
}
