package Protocol;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

// cabeçalho adicional para a pacote q leva stream
// METADADOS
public class FileInfo {
    public int tipoMensagem; /* 0 -> Start video; 1 -> Pause video; 2 -> Stream video */
    public int numSequencia;
    public Set<String> destinos;

    public void toBytes(DataOutputStream dataOut) throws IOException {
        dataOut.writeInt(tipoMensagem);
        if (tipoMensagem == 3) {
            dataOut.writeInt(numSequencia);
            dataOut.writeInt(destinos.size());
            for (String d : destinos) {
                dataOut.writeUTF(d);
            }
        }

    }

    public FileInfo(int tipoMensagem) {
        this.tipoMensagem = tipoMensagem;
    }

    public FileInfo(int tipoMensagem, int num_pacote, Set<String> destinos) {
        this.tipoMensagem = tipoMensagem;
        this.numSequencia = num_pacote;
        this.destinos = destinos;
    }

    public FileInfo(DataInputStream dataIn) throws IOException {
        tipoMensagem = dataIn.readInt();
        if (tipoMensagem == 3) {
            numSequencia = dataIn.readInt();
            int length = dataIn.readInt();
            destinos = new HashSet<>();
            for (int i = 0; i < length; i++) {
                String s = dataIn.readUTF();
                destinos.add(s);
            }
        }
    }


    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("\nTipo mensagem: ").append(this.tipoMensagem)
                .append("\nNum Sequencia: ").append(this.numSequencia);
        if (destinos != null) {
            sb.append("Destinos: ").append(this.destinos);
        } else sb.append("Destinos: ").append(" { }");

        return sb.toString();
    }
}
