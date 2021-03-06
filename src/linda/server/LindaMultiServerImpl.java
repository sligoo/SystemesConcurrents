package linda.server;

import linda.Linda;
import linda.Tuple;
import linda.shm.CentralizedLinda;

import java.rmi.AlreadyBoundException;
import java.rmi.NotBoundException;
import java.rmi.registry.LocateRegistry;
import java.rmi.Naming;
import java.net.MalformedURLException;
import java.rmi.RemoteException;
import java.rmi.server.UnicastRemoteObject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import static linda.server.Task.Instruction.*;


/**
 * Multi-Server Linda implementation
 * Created by jayjader on 1/18/17.
 */
public class LindaMultiServerImpl extends UnicastRemoteObject implements LindaMultiServer {
    public static final long serialVersionUID = 1L;

    private String name;
    private String namingURI;
    private Linda linda;
    private  List<Worker> workers;
    private RemoteList<String> serverRegistry;
    private BlockingQueue<Task> tasks;

    /**
     * Multi-Server implementation of Linda
     * @param namingURI the URI for the Naming server containing the Multi-Server registry
     * @param PORT int
     * @throws RemoteException
     */
    public LindaMultiServerImpl(String namingURI, int PORT) throws RemoteException {
        this.namingURI = namingURI;
        this.linda = new CentralizedLinda();
        this.tasks = new LinkedBlockingQueue<>();

        LocateRegistry.createRegistry(PORT);
        try {
            // Get the server registry, create it if nonexistent
            try {
                this.serverRegistry = (RemoteList<String>) Naming.lookup(this.namingURI + "/ServerRegistry");
            } catch (NotBoundException e) {
                this.serverRegistry = new RemoteListImpl<>();
                Naming.rebind(this.namingURI + "/ServerRegistry", this.serverRegistry);
            }

            // Add ourselves to the registry & Naming server
            this.name = "Server" + this.serverRegistry.size();
            Naming.bind(this.namingURI + "/" + this.name, this);
            this.serverRegistry.add(namingURI + "/" +  this.name);

            this.workers = new ArrayList<>();
        } catch (MalformedURLException  e) {
            e.printStackTrace();
        } catch (AlreadyBoundException e) {
            e.printStackTrace();
            System.exit(1);
        }

        // Launch main "overseer" thread which consumes tasks, spawns workers, and directly handles WRITEs
        Thread overseer = new Thread(() -> {
            while (true) {
                // Consume task. If the queue is empty wait for a new task to appear
                Task currentTask = null;
                try {
                    currentTask = this.tasks.take();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }

                if (currentTask.getInstruction() == WRITE) {
                    // Writes are handled by the main thread
                    this.linda.write(currentTask.getTuple());
                    this.notifyServersTupleWritten();
                } else {
                    // Spawn worker
                    Worker w = new Worker(currentTask, linda, namingURI + "/ServerRegistry");
                    w.start();

                    this.workers.add(w);
                }
            }
        });
        overseer.start();
        System.out.println("Server " + name + " created");
    }

    /**
     * Notifies the other servers a Tuple has been added to the tuplespace
     */
    private void notifyServersTupleWritten() {
        // Fetch size once
        int size = 0;
        try {
            size = this.serverRegistry.size();
        } catch (RemoteException e) {
            e.printStackTrace();
        }

        // Notify the other servers
        for (int i = 0; i < size; i++) {
            try {
                LindaMultiServer server = (LindaMultiServer) Naming.lookup(this.serverRegistry.get(i));
                server.notifyTupleWritten();
            } catch (NotBoundException | MalformedURLException | RemoteException e) {
                e.printStackTrace();
            }
        }
    }

    /** Creates a Task and adds it to the task queue
     */
    private Task createTask(Task.Instruction instruction, Tuple template) {
        Task task = new Task(instruction, template);
        try {
            this.tasks.put(task);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        synchronized (task){
            try {
                System.out.println(task.getTuple() + " waiting");
                task.wait();
                System.out.println(task.getTuple() + " done");
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

        return task;
    }

    /**
     * Adds a task to write a Tuple t to the Linda
     * @param t Tuple
     * @throws RemoteException
     */
    @Override
    public void write(Tuple t) throws RemoteException {
        Task task = new Task(WRITE, t);
        try {
            this.tasks.put(task);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        System.out.print(t);
        System.out.println(" written to " + name);
    }

    /**
     * Adds a blocking task to take a Tuple template from the Linda
     * @param template Tuple
     * @return Tuple
     * @throws RemoteException
     */
    @Override
    public Tuple take(Tuple template) throws RemoteException {
        Task task = this.createTask(TAKE, template);
        return task.getResult();
    }

    /**
     * Adds a blocking task to read a Tuple template from the Linda
     * @param template Tuple
     * @return Tuple
     * @throws RemoteException
     */
    @Override
    public Tuple read(Tuple template) throws RemoteException {
        Task task = this.createTask(READ, template);
        return task.getResult();
    }

    /**
     * Adds a non-blocking task to take a Tuple template from the Linda
     * @param template Tuple
     * @return Tuple
     * @throws RemoteException
     */
    @Override
    public Tuple tryTake(Tuple template) throws RemoteException {
        Task task = this.createTask(TRYTAKE, template);
        return task.getResult();
    }

    @Override
    public Tuple tryRead(Tuple template) throws RemoteException {
        Task task = this.createTask(TRYREAD, template);
        return task.getResult();
    }

    @Override
    public Collection<Tuple> takeAll(Tuple template) throws RemoteException {
        Task task = this.createTask(TAKEALL, template);
        return task.getResultAll();
    }

    @Override
    public Collection<Tuple> readAll(Tuple template) throws RemoteException {
        Task task = this.createTask(READALL, template);
        return task.getResultAll();
    }

    @Override
    public void eventRegister(Linda.eventMode mode, Linda.eventTiming timing, Tuple template, CallbackRemote callback) throws RemoteException {
        this.linda.eventRegister(mode, timing, template, callback.getCallback());
    }

    @Override
    public void debug(String prefix) throws RemoteException {
        this.linda.debug(prefix);
    }

    /**
     * Notifies all the workers that a tuple has been written
     */
    @Override
    public void notifyTupleWritten() throws RemoteException {
        for (Worker w: workers){
            synchronized (w){
                w.notify();
            }

        }
    }

    /**
     * Test function.
     * Initializes 2 servers & a client, tests some functions
     */
    public static void main(String args[]) {
        int PORT = 5555;
        try {
            LocateRegistry.createRegistry(PORT);
            String namingURI = "//localhost:" + PORT;

            LindaMultiServer server0 = new LindaMultiServerImpl(namingURI, 9090);
            LindaMultiServer server1 = new LindaMultiServerImpl(namingURI, 9091);
            LindaMultiServer server2 = new LindaMultiServerImpl(namingURI, 9092);
            LindaMultiServer server3 = new LindaMultiServerImpl(namingURI, 9093);

            LindaClient client0 = new LindaClient(namingURI + "/Server0");
            LindaClient client1 = new LindaClient(namingURI + "/Server1");
            LindaClient client2 = new LindaClient(namingURI + "/Server2");
            LindaClient client3 = new LindaClient(namingURI + "/Server3");

            client0.write(new Tuple(0, 0));
            client1.write(new Tuple(0, 1));
            client2.write(new Tuple(0, 2));
            client3.write(new Tuple(0, 3));

            // Test on client's server
            System.out.println("result client0 : " + client0.take(new Tuple(0, 0)));
            System.out.println("result client1 : " + client1.read(new Tuple(0, 1)));

            // Test propagation
            System.out.println("result client2 : " + client2.read(new Tuple(0, 3)));
            System.out.println("result client3 : " + client3.take(new Tuple(0, 2)));


        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }
}
