package Protocol;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.util.Set;

public class Pacote {
    public int tipoMensagem; /* -1 -> End connection
                             * 0 -> Start connection
                             * 1 -> Routing message
                             * 2 -> Routing activation
                             * 3 -> Streaming multimedia
                             * 4 -> Neighbours activation
                             * 5 -> Ping
                             */
    public FileInfo fileInfo;
    public String origem; // Nome do primeiro nodo a enviar a mensagem
    public String destino; // Nome do nodo destino
    public int numSaltos; // Numero de saltos (usado no pacote de routing)
    public InetAddress ipNodoAnterior; // Obtido a partir do pacote udp (não é enviado no payload UDP)
    public byte[] dados; // Header do pacote inclui o tamanho do payload (em bytes)
    public long startTime;

    //Pacote streaming pedidos
    public Pacote(String origem, String destino, int tipo_msg_file_info) {
        this.tipoMensagem = 3;
        this.origem = origem;
        this.destino = destino;
        this.fileInfo = new FileInfo(tipo_msg_file_info);
        this.dados = null;
    }

    // Pacote streaming de envio de dados
    public Pacote(String origem, int num_seq_file_info, Set<String> destinos, byte[] dados) {
        this.tipoMensagem = 3;
        this.origem = origem;
        this.destino = null;
        this.fileInfo = new FileInfo(3, num_seq_file_info, destinos);
        this.dados = dados;
    }

    // Pacote tipo 6 ping
    public Pacote(int tipoMensagem, String origem, String destino, long s) {
        this.tipoMensagem = tipoMensagem;
        this.origem = origem;
        this.destino = destino;
        this.fileInfo = null;
        this.dados = null;
        this.startTime = s;
    }

    public Pacote(int tipoMensagem, String origem, String destino) {
        this.tipoMensagem = tipoMensagem;
        this.origem = origem;
        this.destino = destino;
        this.fileInfo = null;
        this.dados = null;
    }

    public Pacote(int tipoMensagem, String origem, String destino, int numSaltos, long s) {
        this.tipoMensagem = tipoMensagem;
        this.origem = origem;
        this.destino = destino;
        this.numSaltos = numSaltos;
        this.fileInfo = null;
        this.dados = null;
        this.startTime = s;
    }

    public Pacote(int tipoMensagem, String origem, String destino, byte[] dados) {
        this.tipoMensagem = tipoMensagem;
        this.origem = origem;
        this.destino = destino;
        this.fileInfo = null;
        this.dados = dados;
    }

    public Pacote(InetAddress ip_nodo, byte[] dados) throws IOException {
        bytesToPacket(ip_nodo, dados);
    }

    public byte[] packetToBytes() throws IOException {
        final ByteArrayOutputStream byteOut = new ByteArrayOutputStream();
        final DataOutputStream dataOut = new DataOutputStream(byteOut);
        dataOut.writeInt(tipoMensagem);
        if (fileInfo != null) {
            fileInfo.toBytes(dataOut);
        }
        dataOut.writeUTF(origem);
        if (destino != null)
            dataOut.writeUTF(destino);

        if (tipoMensagem == 1 || tipoMensagem == 2) {
            dataOut.writeInt(numSaltos);
        }
        if (dados != null) {
            dataOut.writeInt(dados.length);
            dataOut.write(dados);
        } else {
            dataOut.writeInt(0);
        }
        if (tipoMensagem == 6 || tipoMensagem == 1 )
            dataOut.writeLong(startTime);
        dataOut.close();
        byteOut.flush();
        return byteOut.toByteArray();
    }

    public void bytesToPacket(InetAddress ipNode, byte[] packetBytes) throws IOException {
        final ByteArrayInputStream byteIn = new ByteArrayInputStream(packetBytes);
        final DataInputStream dataIn = new DataInputStream(byteIn);
        tipoMensagem = dataIn.readInt();
        if (tipoMensagem == 3)
            fileInfo = new FileInfo(dataIn);
        else
            fileInfo = null;
        origem = dataIn.readUTF();
        if (fileInfo == null) {
            destino = dataIn.readUTF();
        }
        else if (fileInfo.tipoMensagem != 3) {
            destino = dataIn.readUTF();
        }

        if (tipoMensagem == 1 || tipoMensagem == 2) {
            numSaltos = dataIn.readInt();
        }
       
        int dataBytes = dataIn.readInt();
        if (dataBytes > 0) {
            dados = dataIn.readNBytes(dataBytes);
        } else
            dados = null;
        if (tipoMensagem == 6 || tipoMensagem == 1 )
            startTime = dataIn.readLong();
        dataIn.close();
        byteIn.close();
        ipNodoAnterior = ipNode;
    }

    @Override
    public String toString() {
        return "{" +
                " tipoMensagem='" + tipoMensagem + "'" +
                ", origem='" + origem + "'" +
                ", destino='" + destino + "'" +
                ", ipNodoAnterior='" + ipNodoAnterior.getHostAddress() + "'" +
                ", fileInfo='" + fileInfo + "'" +
                ", dados='" + dados + "'" +
                "}";
    }
}
