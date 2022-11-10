import java.net.InetAddress;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

public class Ping {
    private Timer timer;
    private Map<InetAddress,Integer> tabela_pings;
    private ReentrantLock lock;
    private Condition condition;

    public Ping(int seconds, Map<InetAddress,Integer> tabela_pings, ReentrantLock lock, Condition condition) {
        timer = new Timer();
        timer.schedule(new HandlerPing(), seconds*1000); // schedule the task
        this.tabela_pings = tabela_pings;
        this.lock = lock;
        this.condition = condition;
    }

    class HandlerPing extends TimerTask {
        public void run() {

            int max = getmaxValue();
            int min = max;

            InetAddress client_min = null;

            try {
                lock.lock();

                for (InetAddress p : tabela_pings.keySet()) {
                    if (tabela_pings.get(p) < min) {
                        min = tabela_pings.get(p);
                        client_min = p;
                    }
                }

                if (max - min >= 3) {
                    tabela_pings.put(client_min,-1);
                    condition.signalAll(); // notificar o nodo que tem de DESATIVAR conexao
                }

            } finally {
                lock.unlock();
            }

            new Ping(3,tabela_pings, lock, condition); //Terminate the timer thread

        }
    }


    public int getmaxValue() { //obtém o número maximo de tabela_pings
        int max = 0;

        for (InetAddress p : tabela_pings.keySet()) {
            if(tabela_pings.get(p) > max){
                max = tabela_pings.get(p);
            }
        }
        return max;
    }

}
