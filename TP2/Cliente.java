/* ------------------
   Cliente
   usage: java Cliente
   adaptado dos originais pela equipa docente de ESR (nenhumas garantias)
   colocar o cliente primeiro a correr que o servidor dispara logo!
   ---------------------- */

import java.awt.*;
import java.awt.event.ActionListener;
import java.io.IOException;
import java.awt.event.*;
import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import javax.swing.*;

import Protocol.Pacote;

public class Cliente extends Node{

    //GUI
    //----
    private static JFrame f;
    private static JButton pingButton;
    private static JButton playButton;
    private static JButton pauseButton;
    private static JButton tearButton;
    private static JPanel mainPanel;
    private static JPanel buttonPanel;
    private static JLabel iconLabel;
    private static ImageIcon icon;

    private static Timer cTimer;

    // CLiente Data
    private boolean temRota = false;
    private ReentrantLock streamLock;
    public Map<Integer, Pacote> dadosStream;
    public int imagemAtual = -1;
    public int numImagens = 0;
    public final int numMaximo = 20;
    public boolean running = false;

    long startTime;
    public ArrayList<Long> mediaListBootstrapper = new ArrayList<>();
    public ArrayList<Long> mediaListBootstrapperAlternativo = new ArrayList<>();
    public long mediaBootstrapper = Long.MAX_VALUE;
    public long mediaBootstrapperAlternativo = Long.MAX_VALUE;
    public String melhorServ;

    //Constructor
    //--------------------------
    public Cliente(String[] args) throws Exception {
        super(args);
        dadosStream = new HashMap<>();
        streamLock = new ReentrantLock();
        melhorServ = bootstrapper.idNodo;

        //build GUI
        //--------------------------
        f = new JFrame("Cliente Testes");
        pingButton = new JButton("Ping");
        playButton = new JButton("Play");
        pauseButton = new JButton("Pause");
        tearButton = new JButton("Teardown");
        mainPanel = new JPanel();
        buttonPanel = new JPanel();


        //Frame
        f.addWindowListener(new WindowAdapter() {
            public void windowClosing(WindowEvent e) {
                cTimer.stop();
                try {
                    encerra_conexao();
                } catch (IOException e1) {
                    System.out.println(e1.getMessage());
                }
                System.out.println("Encerrando conexao...");
                System.exit(0);
            }
        });


        //Buttons
        buttonPanel.setLayout(new GridLayout(1, 0));
        buttonPanel.add(pingButton);
        buttonPanel.add(playButton);
        buttonPanel.add(pauseButton);
        buttonPanel.add(tearButton);

        // handlers
        playButton.addActionListener(new playButtonListener());
        tearButton.addActionListener(new tearButtonListener());
        pauseButton.addActionListener(new pauseButtonListener());
        pingButton.addActionListener(new pingButtonListener());

        //Image display label
        icon = new ImageIcon();
        iconLabel = new JLabel(icon);

        //frame layout
        mainPanel.setLayout(null);
        mainPanel.add(iconLabel);
        mainPanel.add(buttonPanel);
        iconLabel.setBounds(110, 40, 380, 280);
        buttonPanel.setBounds(0, 362, 600, 50);


        f.getContentPane().add(mainPanel, BorderLayout.CENTER);
        f.setSize(new Dimension(610, 450));
        f.setVisible(true);

        //init para a parte do cliente
        //--------------------------
        cTimer = new Timer(20, new clientTimerListener());
        cTimer.setInitialDelay(0);
        cTimer.setCoalesce(true);
        start();
    }

        public static void main(String[] args) {
            try {
                Cliente t = new Cliente(args);
            } catch (Exception e) {
                System.out.println(e.getMessage());
                System.exit(0);
            }
    }


    // Resposta a cada pacote recebido
    public void trataMensagensRecebidas(int tipoMensagem, Pacote pacoteRecebido) throws IOException {
        switch (tipoMensagem) {
            case 0: // recebe os seus vizinhos
                //System.out.println("Recebi mensagem do tipo 0 do nodo " + pacoteRecebido.origem);
                new Thread(() -> {
                    try {
                        recebeVizinhhos(pacoteRecebido);
                        gera_rota();
                    } catch (IOException e) {
                        System.out.println(e.getMessage());
                    }

                }).start();
                break;

            // tipo 1 - atualizar rotas nao é necessario nos clientes

            case 2: // Mensagem de ativacao da rota
                //System.out.println("Recebi mensagem do tipo 2 do nodo " + pacoteRecebido.origem);

                temRota = true;
                System.out.println("Nova rota gerada.");
                break;

            case 3: // Rececao de frames de dados multimedia
                //System.out.println("Recebi mensagem do tipo 3 do nodo " + pacoteRecebido.origem);

                new Thread(() -> {
                    try {
                        recebe_multimedia(pacoteRecebido);
                    } catch (Exception e) {
                        System.out.println(e.getMessage());
                    }
                }).start();

                break;

            case 4: // Conexao de um novo vizinho
                //System.out.println("Recebi mensagem do tipo 4 do nodo " + pacoteRecebido.origem);

                new Thread(() -> {
                    try {
                        System.out.println("Novo vizinho conectado: " + pacoteRecebido.origem);
                        super.ativa_interface_vizinho(pacoteRecebido.origem);
                       // gera_rota();
                    } catch (IOException e) {
                        System.out.println(e.getMessage());
                    }

                }).start();
                break;

            case 5: // Ping
                //System.out.println("Recebi mensagem do tipo 5 do nodo " + pacoteRecebido.origem);

                new Thread(() -> {

                    System.out.println(new String(pacoteRecebido.dados) + " enviados por " + pacoteRecebido.origem);
                    long tempoDecorridoMs = System.currentTimeMillis()-startTime;
                    System.out.println("Ping: " + tempoDecorridoMs + " ms");
                    try {
                        gera_rota();
                    } catch (IOException e) {
                        System.out.println(e.getMessage());
                    }

                }).start();
                break;

            case 6: // Ping Periodico
                //System.out.println("Recebi mensagem do tipo 6 do nodo " + pacoteRecebido.origem);

                new Thread(() -> {
                    long tempoDecorridoMs = System.currentTimeMillis()-pacoteRecebido.startTime;
                    System.out.println("Ping periodico enviado por "+ pacoteRecebido.origem + ": " + tempoDecorridoMs + " ms");

                    if (bootstrapperAlternativo != null) {
                        if (pacoteRecebido.origem.equals(bootstrapperAlternativo.idNodo)) {
                            // se for o servidor alternativo

                            if (this.mediaListBootstrapperAlternativo.size() < 5) {
                                this.mediaListBootstrapperAlternativo.add(tempoDecorridoMs);
                            }else {
                                long media = 0;
                                for (long l : this.mediaListBootstrapperAlternativo) {
                                    media += l;
                                }
                                mediaBootstrapperAlternativo = media / 5;
                                if (tempoDecorridoMs > mediaBootstrapperAlternativo + 100 ) {
                                    System.out.println("Suspeita de rota sobrecarregada para servidor ALternativo!");
                                    // volta a gerar rotas
                                    try {
                                        gera_rota();
                                    } catch (IOException e) {
                                        System.out.println(e.getMessage());
                                    }

                                }

                                this.mediaListBootstrapperAlternativo.remove(0);
                                this.mediaListBootstrapperAlternativo.add(tempoDecorridoMs);
                            }


                        }
                    }

                    if (pacoteRecebido.origem.equals(bootstrapper.idNodo)) {
                        // se for o servidor principal
                        if (this.mediaListBootstrapper.size() < 5) {
                            this.mediaListBootstrapper.add(tempoDecorridoMs);
                        }else {
                            long media = 0;
                            for (long l : this.mediaListBootstrapper) {
                                media += l;
                            }
                            mediaBootstrapper = media / 5;
                            if (tempoDecorridoMs > mediaBootstrapper + 100 ) {
                                System.out.println("Suspeita de rota sobrecarregada!");
                                // volta a gerar rotas
                                try {
                                    gera_rota();
                                } catch (IOException e) {
                                    System.out.println(e.getMessage());
                                }

                            }

                            this.mediaListBootstrapper.remove(0);
                            this.mediaListBootstrapper.add(tempoDecorridoMs);
                        }
                    }




                }).start();
                break;

            case -1:
                //System.out.println("Recebi mensagem do tipo -1 do nodo " + pacoteRecebido.origem);

                new Thread(() -> {
                    try {
                        encerra_conexao_nodo(pacoteRecebido);
                        //gera_rota();
                    } catch (IOException e) {
                        System.out.println(e.getMessage());
                    }

                }).start();
                break;

            default:
                break;
        }
    }

    // Rececao de dados multimedia
    public void recebe_multimedia(Pacote pacoteRecebido) {
        streamLock.lock();
        try {
            if (running) {
                if (imagemAtual == -1)
                    imagemAtual = pacoteRecebido.fileInfo.numSequencia;
                dadosStream.put(pacoteRecebido.fileInfo.numSequencia,pacoteRecebido);
            }
            else {
                if (imagemAtual != -1)
                    imagemAtual = -1;
                if (!dadosStream.isEmpty()) {
                    dadosStream.clear();
                }
            }
        }
        finally {
            streamLock.unlock();
        }

    }

    public Pacote getPacoteStream() {
        Pacote p = null;
        streamLock.lock();
        try {
            if (running && !dadosStream.isEmpty()) {
                p = dadosStream.get(imagemAtual);
                dadosStream.remove(imagemAtual);
                imagemAtual++;
                numImagens++;
                if (numImagens >= numMaximo) {
                    imagemAtual = -1;
                    numImagens = 0;
                }
            }
        }
        finally {
            streamLock.unlock();
        }
        return p;
    }

    public void atualizaVideo(int tipo_metadados) throws IOException {
        if (temRota) {
            if (tipo_metadados == 0) { // play
                if (!running) {
                    System.out.println("media para bootsrapper " + mediaBootstrapper);
                    System.out.println("media para bootstrapper alternativo " + mediaBootstrapperAlternativo);
                    if (mediaBootstrapperAlternativo < mediaBootstrapper )
                        melhorServ = bootstrapperAlternativo.idNodo;
                    else
                        melhorServ = bootstrapper.idNodo;

                    System.out.println("Servidor com melhores condições : " + melhorServ);
                    Pacote p = new Pacote(idNodo, melhorServ, tipo_metadados);
                    streamLock.lock();
                    super.enviaTodosVizinhos(p);
                    imagemAtual = -1;
                    running = true;
                    streamLock.unlock();
                }
            }
            else { // pause
                if (running) {
                    Pacote p = new Pacote(idNodo, melhorServ, tipo_metadados);
                    super.enviaTodosVizinhos(p);
                    streamLock.lock();
                    running = false;
                    numImagens = 0;
                    if (!dadosStream.isEmpty()) {
                        dadosStream.clear();
                    }
                    streamLock.unlock();
                }
            }
        }
        else {
            System.out.println("Nao existe rota disponivel para " + melhorServ + "!");
        }
    }

    // Resposta ao fim de conexao de um nodo da rede
    public void encerra_conexao_nodo(Pacote p) throws IOException{
        if (p.origem.equals(bootstrapper.idNodo)) {
            System.out.println("Bootstrapper encerrou a conexão.");
        }
        else {
            System.out.println(p.origem + " encerrou a conexão!");
            super.desativa_interface_vizinho(p.origem);
            temRota = false;
        }
    }

    // Calcula o caminho mais rapido do nodo ao servidor
    public void gera_rota() throws IOException {
        long startTime = System.currentTimeMillis();
        Pacote p = new Pacote(1, this.idNodo, bootstrapper.idNodo,0, startTime);
        super.enviaTodosVizinhos(p);
        System.out.println("A gerar nova rota...");

        if (this.bootstrapperAlternativo != null) {
            Pacote pA = new Pacote(1, this.idNodo, bootstrapperAlternativo.idNodo, 0, startTime);
            super.enviaTodosVizinhos(pA);
            System.out.println("A gerar nova rota para servidor Alternativo...");
        }

    }

    // Mensagem de ping para o bootstrapper
    public void enviaMsgPing() throws IOException {
        startTime = System.currentTimeMillis();
        Pacote p = new Pacote(5, idNodo, bootstrapper.idNodo);
        super.enviaTodosVizinhos(p);

        if (this.bootstrapperAlternativo != null) {
            Pacote pA = new Pacote(5, idNodo, bootstrapperAlternativo.idNodo);
            super.enviaTodosVizinhos(pA);
            System.out.println("A gerar nova rota para servidor Alternativo...");
        }
    }

    public void encerra_conexao() throws IOException {
        Pacote p = new Pacote(-1, idNodo, bootstrapper.idNodo);
        super.enviaTodosVizinhos(p);
        if (bootstrapperAlternativo != null){
            Pacote pA = new Pacote(-1, idNodo, bootstrapperAlternativo.idNodo);
            super.enviaTodosVizinhos(p);
        }
    }


    //------------------------------------
    //Handler for buttons
    //------------------------------------

    //Handler for Play button
    //-----------------------
    class playButtonListener implements ActionListener {
        public void actionPerformed(ActionEvent e){
            System.out.println("Play Button pressed !");
            // start the timers ...
            try {
                atualizaVideo(0);
                cTimer.start();
            } catch (IOException e1) {
                System.out.println(e1.getMessage());
            }
        }
    }

    //Handler for Tear button
    //-----------------------
    class tearButtonListener implements ActionListener {
        public void actionPerformed(ActionEvent e){
            try {
                System.out.println("Teardown Button pressed !");
                cTimer.stop();
                encerra_conexao();
                System.out.println("Encerrando conexao...");
                // exit
                System.exit(0);
            } catch (IOException e1) {
                System.out.println(e1.getMessage());
            }
        }
    }

    //Handler for Pause button
    //-----------------------
    class pauseButtonListener implements ActionListener {
        public void actionPerformed(ActionEvent e){
            System.out.println("Pause Button pressed !");
            // start the timers ...
            try {
                atualizaVideo(1);
                cTimer.stop();
            } catch ( IOException e1) {
                System.out.println(e1.getMessage());
            }
        }
    }

    //Handler for Setup button
    //-----------------------
    class pingButtonListener implements ActionListener {
        public void actionPerformed(ActionEvent e){
            System.out.println("Ping Button pressed !");
            try {
                enviaMsgPing();
            } catch (IOException ex) {
                throw new RuntimeException(ex);
            }
        }
    }


    //------------------------------------
    //Handler for timer (para cliente)
    //------------------------------------
    class clientTimerListener implements ActionListener {
        public void actionPerformed(ActionEvent e) {

            // get an Image object from the payload bitstream
            if (!dadosStream.isEmpty()) {
                Pacote p = getPacoteStream();
                if (p != null && p.dados != null) {
                    // display Video
                    Toolkit toolkit = Toolkit.getDefaultToolkit();
                    Image image = toolkit.createImage(p.dados, 0, p.dados.length);
                    icon = new ImageIcon(image);
                    iconLabel.setIcon(icon);
                }
            }
        }
    }
}