
import Protocol.Interface;
import Protocol.Pacote;

import java.io.IOException;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.*;

public class Router extends Node {
    private Map<String, Map<InetAddress, TableEntry>> tabela_encaminhamento;

    public Router(String[] args) throws UnknownHostException, SocketException {
        super(args);

        Runtime.getRuntime().addShutdownHook(new Thread() {
            public void run() {
                System.out.println("Encerrando conexao...");
                encerra_conexao();
            }
        });
    }

    public static void main(String[] args) {

        try {
			Router rt = new Router(args);
            rt.start();
		} catch (Exception e) {
            System.out.println(e.getMessage());
            System.exit(0);
		}   
    }


    // Resposta a cada pacote recebido
    public void trataMensagensRecebidas(int messageType, Pacote pacote_recebido) throws Exception {
        switch (messageType) {
            case 0: // Inicio de conexao
                //System.out.println("Recebi mensagem do tipo 0 do nodo " + pacote_recebido.origem);
                new Thread(() -> {
                    try {
                        recebeVizinhhos(pacote_recebido);
                        atualizar_tabelaEnc_inicial();
                    } catch (IOException e) {
                        System.out.println(e.getMessage());
                    }
                }).start();

                break;

            case 1: // Mensagem de routing
                //System.out.println("Recebi mensagem do tipo 1 do nodo " + pacote_recebido.origem);
                new Thread(() -> {
                    try {
                        atualizar_tabelaEnc(pacote_recebido);
                        enviarPacoteRouting(pacote_recebido);
                    } catch (IOException e) {
                        System.out.println(e.getMessage());
                    }
                }).start();

                break;

            case 2: // Mensagem de ativacao da rota
               // System.out.println("Recebi mensagem do tipo 2 do nodo " + pacote_recebido.origem);

                new Thread(() -> {
                    try {
                        ativar_novas_interfaces(pacote_recebido);
                    } catch (Exception e) {
                        System.out.println(e.getMessage());
                    }
                }).start();
                break;

            case 3: // Stream multimedia
                //System.out.println("Recebi mensagem do tipo 3 do nodo " + pacote_recebido.origem);

                new Thread(() -> {
                    try {
                        trataMensagensMultimedia(pacote_recebido);
                    } catch (Exception e) {
                        System.out.println(e.getMessage());
                    }
                }).start();
                break;

            case 4: // Ativacao de um vizinho
                //System.out.println("Recebi mensagem do tipo 4 do nodo " + pacote_recebido.origem);

                new Thread(() -> {
                    try {
                        super.ativa_interface_vizinho(pacote_recebido.origem);
                        System.out.println("Novo Vizinho conectado: " + pacote_recebido.origem);
                    } catch (Exception e) {
                        System.out.println(e.getMessage());
                    }
                }).start();

                break;

            case 5: // Ping message
                //System.out.println("Recebi mensagem do tipo 5 do nodo " + pacote_recebido.origem);

                new Thread(() -> {
                    try {
                        send(pacote_recebido);
                    } catch (Exception e) {
                        System.out.println(e.getMessage());
                    }
                }).start();
                break;

            case 6: // Ping periodico
                //System.out.println("Recebi mensagem do tipo 6 (ping periodico) do nodo " + pacote_recebido.origem + "STARTTIME " + pacote_recebido.startTime);

                new Thread(() -> {
                    try {
                        send(pacote_recebido);
                    } catch (Exception e) {
                        System.out.println(e.getMessage());
                    }
                }).start();
                break;

            case -1:
                //System.out.println("Recebi mensagem do tipo -1 do nodo " + pacote_recebido.origem);

                new Thread(() -> {
                    try {
                        encerra_conexao_nodo(pacote_recebido);
                        System.out.println("Nodo encerrou conexão: " + pacote_recebido.origem);
                    } catch (Exception e) {
                        System.out.println(e.getMessage());
                    }
                }).start();
                break;

            default:
                break;
        }
    }


    public void trataMensagensMultimedia(Pacote p) throws Exception {
        if (p.fileInfo.tipoMensagem == 3) { // Se for pacote de streaming de dados
            Map<InetAddress, Set<String>> dests = new HashMap<>();
            for (String node : p.fileInfo.destinos) {
                InetAddress ip = obter_rota_ativa(node,null);

                if (ip != null) {
                    Set<String> list = dests.get(ip);
                    if (list == null)
                        list = new HashSet<>();
                    list.add(node);
                    dests.put(ip, list);
                }

            }
            for (Map.Entry<InetAddress,Set<String>> e : dests.entrySet()) {
                Pacote newP = new Pacote(p.origem, p.fileInfo.numSequencia, e.getValue(), p.dados);
                super.send(newP, e.getKey());
                System.out.println("Packet -> Proximo salto: " + e.getKey() + ", Destinos: " + e.getValue().toString());
            }
        }
        else {
            send(p);
        }
    }

    // Resposta ao fim de conexao de um nodo da rede
    public void encerra_conexao_nodo(Pacote p) throws IOException {
        if (p.origem.equals(bootstrapper.idNodo)) {
            String id_nodo_anterior = super.getIdVizinho(p.ipNodoAnterior);
            super.desativa_interface_vizinho(id_nodo_anterior);
            super.enviaTodosVizinhos(p);
            System.out.println("Bootstrapper encerrou a conexão.");
        }
        else {

            send(p);

            if (super.e_vizinho(p.origem)) {
                desativa_vizinho(p.origem);
            }
            else {
                if (!super.e_router(p.origem)) {
                    for (TableEntry te : tabela_encaminhamento.get(p.origem).values()) {
                        te.estado = false;
                    }
                } else { // se for router
                    System.out.println(p.origem + "origem " + p.ipNodoAnterior.getHostAddress());
                    System.out.println("é router");
                    System.out.println(p.ipNodoAnterior);

                }
            }
        }

    }

    public void desativa_vizinho(String idnode) throws IOException {
        super.desativa_interface_vizinho(idnode);
        InetAddress ip = super.vizinhos.get(idnode).enderecoIP;
        for (String id : tabela_encaminhamento.keySet()) {
            TableEntry te = tabela_encaminhamento.get(id).get(ip);
            if (te != null)
                te.estado = false;
        }
    }


    public void encerra_conexao() {
        Pacote p = new Pacote(-1, idNodo, bootstrapper.idNodo);
        super.enviaTodosVizinhos(p);
    }

    // Direcionar os pacotes recebidos para o proximo vizinho
    public void send(Pacote p) throws IOException {
        InetAddress dest = obter_rota_ativa(p.destino,p);
        super.send(p, dest);

    }

    public InetAddress obter_rota_ativa(String dest, Pacote p) throws IOException {
        Map<InetAddress, TableEntry> entry = tabela_encaminhamento.get(dest);
        InetAddress address = null;
        if (entry != null) {
            //long tempo = 0;
            for (TableEntry e : entry.values()) {
                //System.out.println("Rota ativa para " + dest + " pelo prox salto " ); //+ e.proxSalto + " tempo " + e.custoTempo);
                if (e.estado == true) {
                    address = e.proxSalto;
                    break;
                }
            }
            //System.out.println("Prox salto " + address + "com tempo "+  tempo);
        }

        if (address == null && p != null) {
            System.out.println("Nao existe rota disponivel para " + dest + "!");
            System.out.println("A enviar para todos os vizinhos...");
            super.enviaTodosVizinhosExceto(p, p.ipNodoAnterior);
        }
        return address;
    }

    // Update initial routing table
    public void atualizar_tabelaEnc_inicial() throws UnknownHostException {
        this.tabela_encaminhamento = new HashMap<>();
        for (Interface i : super.vizinhos.values()) {
            TableEntry e = new TableEntry(i.idNodo, i.enderecoIP, 1, i.estado);
            Map<InetAddress, TableEntry> entry = new HashMap<>();
            entry.put(e.proxSalto, e);
            tabela_encaminhamento.put(e.destino, entry);
        }
    }

    // Atualizar tabela de routing
    public void atualizar_tabelaEnc(Pacote p) throws UnknownHostException {
        Map<InetAddress, TableEntry> entry = tabela_encaminhamento.get(p.origem);

        long tempoDecorridoMs = System.currentTimeMillis()- p.startTime;


        if (entry == null) {
            entry = new HashMap<>();
            TableEntry e = new TableEntry(p.origem, p.ipNodoAnterior, p.numSaltos + 1,tempoDecorridoMs);
            entry.put(e.proxSalto, e);
            tabela_encaminhamento.put(p.origem, entry);
        } else {
            TableEntry e = entry.get(p.ipNodoAnterior);
            if (e == null) {
                e = new TableEntry(p.origem, p.ipNodoAnterior, p.numSaltos + 1,tempoDecorridoMs);
                entry.put(e.proxSalto, e);
            } else {
                if (p.numSaltos + 1 < e.nSaltos) {
                    e.nSaltos = p.numSaltos + 1;
                }

                e.custoTempo = tempoDecorridoMs;
            }
        }
    }

    // Obter entrada da tabela com menor custo
    public TableEntry obter_entrada_menor_custo(String dest) {
        TableEntry minCost = null;
        Map<InetAddress, TableEntry> entries = tabela_encaminhamento.get(dest);
        for (TableEntry r : entries.values()) {
            r.estado = false;
            if (minCost == null)
                minCost = r;
            else {
                // se  o r.custo for menor que 90% do minCost
                if (r.custoTempo  <= (minCost.custoTempo * 0.90))
                    minCost = r;
                // se o r.custo de tempo tiver dentro de +- 10% do minCost olhamos para os saltos (90% a 110% do minCost)
                if (r.custoTempo > (minCost.custoTempo * 0.90) && r.custoTempo < (minCost.custoTempo * 1.10) ){
                    if (r.nSaltos < minCost.nSaltos)
                        minCost = r;
                }

            }
        }
        return minCost;
    }

    // Escolhe o router cujo caminho é mais perto
    public TableEntry obter_entrada_menor_custo(String dest, List<InetAddress> routers) {
        TableEntry minCost = null;
        for (InetAddress r : routers) {
            TableEntry entry = tabela_encaminhamento.get(dest).get(r);
            entry.estado = false;
            if (minCost == null)
                minCost = entry;
            else {
                // se  o entry.custo for menor que 90% do minCost
                if (entry.custoTempo  <= (minCost.custoTempo * 0.90))
                    minCost = entry;
                // se o entry.custo de tempo tiver dentro de +- 10% do minCost olhamos para os saltos (90% a 110% do minCost)
                if (entry.custoTempo > (minCost.custoTempo * 0.90) && entry.custoTempo < (minCost.custoTempo * 1.10) ){
                    if (entry.nSaltos < minCost.nSaltos)
                        minCost = entry;
                }

            }
        }
        return minCost;
    }


    // Spread Routing Packet
    public void enviarPacoteRouting(Pacote p) throws IOException {
        List<InetAddress> routers = super.getVizinhosRouter();
        // router tem pelo menos 1 vizinho
        if (routers.size() > 0) {

            Set<InetAddress> entryRouters = new HashSet<>();

            if (tabela_encaminhamento.get(p.origem) != null)
                entryRouters = tabela_encaminhamento.get(p.origem).keySet(); // Routers de quem ja ha mensagem de routing

            if (super.e_vizinho(p.destino)) { // O destino é um dos seus vizinhos
                boolean receivedAll = true;
                for (InetAddress r : routers) {
                    if (!entryRouters.contains(r)) {
                        receivedAll = false;
                        break;
                    }
                }

                if (receivedAll) { // Se ja recebeu mensagem de routing de todos os vizinhos
                    TableEntry minCost = obter_entrada_menor_custo(p.origem, routers);
                    Pacote rp = new Pacote(1, p.origem, p.destino, minCost.nSaltos, p.startTime);
                    super.send(rp, p.destino); // Envia mensagem diretamente ao destino
                }
            } else { // O destino nao é um dos seus vizinhos
                for (InetAddress r : routers) {
                    if (!entryRouters.contains(r)) {
                        TableEntry minCost = obter_entrada_menor_custo(p.origem);
                        Pacote rp = new Pacote(1, p.origem, p.destino, minCost.nSaltos, p.startTime);
                        super.send(rp, r); // Envia aos routers vizinhos
                    }
                }
            }
        } else {
                Pacote rp = new Pacote(1, p.origem, p.destino, p.numSaltos + 1 , p.startTime);
                super.send(rp, p.destino); // Envia mensagem diretamente ao destino
        }
    }

    public void ativar_novas_interfaces(Pacote p) throws IOException {

        Map<InetAddress, TableEntry> bootstrapperEntry = tabela_encaminhamento.get(p.origem);

        if (bootstrapperEntry == null) {
            bootstrapperEntry = new HashMap<>();
            TableEntry e = new TableEntry(p.origem, p.ipNodoAnterior, p.numSaltos + 1);//,tempoDecorridoMs);
            e.ativa_interface();
            bootstrapperEntry.put(e.proxSalto, e);
            tabela_encaminhamento.put(p.origem, bootstrapperEntry);
        } else {
            TableEntry e = obter_entrada_menor_custo(p.origem);
            e.ativa_interface();
        }
        // ativou interface do bootstraper ate este router


        // vai ativar interface do router para destino que e cliente
        TableEntry entry = obter_entrada_menor_custo(p.destino);
        entry.ativa_interface();
        Pacote newP = new Pacote(2, p.origem, p.destino, p.numSaltos + 1);
        super.send(newP, entry.proxSalto);
        System.out.print("Entrada ativada ->");
        System.out.println(p.destino + ": " + entry.proxSalto.toString());
    }


}
