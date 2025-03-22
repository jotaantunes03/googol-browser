package search;

import search.Sockets.ReliableMulticast;

import java.io.IOException;
import java.net.*;
import java.rmi.*;
import java.rmi.server.*;
import java.rmi.registry.*;
import java.sql.*;
import java.util.*;
import java.io.File;
import java.util.concurrent.CopyOnWriteArraySet;


/**
 * Classe responsável por armazenar e gerir o índice invertido de um motor de pesquisa.
 * Esta implementação utiliza uma base de dados SQLite para armazenar palavras indexadas
 * e as ligações entre páginas, garantindo persistência e escalabilidade.
 *
 * A classe também se conecta ao servidor da `URLQueue`, onde mantém uma fila de URLs
 * a serem processados pelos downloaders.
 */
public class IndexStorageBarrel extends UnicastRemoteObject implements IndexStorageBarrelInterface {

    //----------------------------------------ATTRIBUTES----------------------------------------

    /** Interface RMI para comunicação com a URLQueue */
    private static URLQueueInterface urlQueueInterface;

    /** Ligação à base de dados SQLite */
    private Connection connection;

    private static final String GROUP_ADDRESS = "230.0.0.0";
    private static final int PORT = 4446; // Porta Multicast
    private static ReliableMulticast multicast;






    //----------------------------------------CONSTRUCTOR----------------------------------------
    /**
     * Construtor da classe `IndexStorageBarrel`.
     * Inicializa a base de dados e verifica se as tabelas necessárias existem.
     * Se a base de dados não existir, cria-a dentro da pasta `Index_BD`.
     *
     * @throws RemoteException Caso ocorra um erro na comunicação remota RMI.
     */
    public IndexStorageBarrel() throws RemoteException {
        super();
        try {
            multicast = new ReliableMulticast(GROUP_ADDRESS, PORT);

            System.out.println("IndexStorageBarrel conectado ao grupo multicast.");

            setupDatabase();

        } catch (IOException e) {
            System.err.println("Erro ao configurar multicast: " + e.getMessage());
            e.printStackTrace();
        }


    }






    //----------------------------------------DATABASE METHODS----------------------------------------
    /**
     * Configura a base de dados SQLite dentro da pasta `Index_BD`.
     * Caso a base de dados não exista, cria-a e define as tabelas necessárias.
     */
    private void setupDatabase() {
        try {
            // Criar a pasta "Index_BD" se não existir
            File directory = new File("../Index_BD");
            if (!directory.exists()) {
                directory.mkdir();
            }

            // Conectar à base de dados SQLite
            String dbPath = "jdbc:sqlite:../Index_BD/index_storage.db";
            connection = DriverManager.getConnection(dbPath);
            System.out.println("Ligado à base de dados do Storage Barrel.");

            // Criar tabelas se não existirem
            try (Statement stmt = connection.createStatement()) {
                stmt.execute("PRAGMA journal_mode=WAL;");

                stmt.execute("CREATE TABLE IF NOT EXISTS index_data (" +
                        "word TEXT PRIMARY KEY, " +
                        "urls TEXT)");

                stmt.execute("CREATE TABLE IF NOT EXISTS links_graph (" +
                        "source_url TEXT, " +
                        "linked_url TEXT, " +
                        "PRIMARY KEY (source_url, linked_url))");
            }

        } catch (SQLException e) {
            System.err.println("Erro ao configurar a base de dados: " + e.getMessage());
            e.printStackTrace();
        }
    }






    //----------------------------------------METHODS----------------------------------------
    public void listen() {
        try {
            while (true) {
                String message = multicast.receiveMessage();
                String[] parts = message.split(":");
                if (parts.length == 2) {
                    addToIndex(parts[0], parts[1]);
                } else if (parts.length == 3) {
                    addLink(parts[1], parts[2]);
                }
            }
        } catch (IOException | SQLException e) {
            System.err.println("Erro na recepção de dados multicast: " + e.getMessage());
            e.printStackTrace();
        }
    }


    @Override
    public synchronized void addToIndex(String word, String url) throws RemoteException, SQLException {
        try {
            connection.setAutoCommit(false);  // Iniciar transação

            String existingUrls = null;

            // Verificar se a palavra já está na base de dados
            try (PreparedStatement stmt = connection.prepareStatement("SELECT urls FROM index_data WHERE word = ?")) {
                stmt.setString(1, word);
                ResultSet rs = stmt.executeQuery();
                if (rs.next()) {
                    existingUrls = rs.getString("urls");
                }
            }

            // Atualizar ou inserir a palavra com os URLs associados
            if (existingUrls != null) {
                if (!existingUrls.contains(url)) {
                    existingUrls += ";" + url;
                    try (PreparedStatement updateStmt = connection.prepareStatement("UPDATE index_data SET urls = ? WHERE word = ?")) {
                        updateStmt.setString(1, existingUrls);
                        updateStmt.setString(2, word);
                        updateStmt.executeUpdate();
                    }
                }
            } else {
                try (PreparedStatement insertStmt = connection.prepareStatement("INSERT INTO index_data (word, urls) VALUES (?, ?)")) {
                    insertStmt.setString(1, word);
                    insertStmt.setString(2, url);
                    insertStmt.executeUpdate();
                }
            }
            connection.commit();  // Confirmar a transação

        } catch (SQLException e) {
            connection.rollback();
            e.printStackTrace();
        }
    }


    @Override
    public List<String> searchWord(String word) throws RemoteException {
        List<String> results = new ArrayList<>();
        try (PreparedStatement stmt = connection.prepareStatement("SELECT urls FROM index_data WHERE word = ?")) {
            stmt.setString(1, word);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                String[] urls = rs.getString("urls").split(";");
                results.addAll(Arrays.asList(urls));
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return results;
    }


    @Override
    public void addLink(String sourceUrl, String linkedUrl) throws RemoteException {
        try (PreparedStatement stmt = connection.prepareStatement("INSERT OR IGNORE INTO links_graph (source_url, linked_url) VALUES (?, ?)")) {
            stmt.setString(1, sourceUrl);
            stmt.setString(2, linkedUrl);
            stmt.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }


    @Override
    public boolean isUrlIndexed(String url) throws RemoteException {
        try (PreparedStatement stmt = connection.prepareStatement("SELECT 1 FROM index_data WHERE urls LIKE ? LIMIT 1")) {
            stmt.setString(1, "%" + url + "%");
            ResultSet rs = stmt.executeQuery();
            return rs.next();
        } catch (SQLException e) {
            e.printStackTrace();
            return false;
        }
    }






    //----------------------------------------MAIN----------------------------------------
    /**
     * Método principal que inicia o servidor `IndexStorageBarrel` e conecta-se à `URLQueue`.
     *
     * @param args Argumentos da linha de comandos.
     */
    public static void main(String args[]) {
        try {

            if (args.length < 1) {
                System.err.println("Por favor, especifique a porta como argumento.");
                return;
            }

            int port = Integer.parseInt(args[0]); // Porta do registro RMI

            // Iniciar o servidor RMI
            IndexStorageBarrel server = new IndexStorageBarrel();

            // Criar ou conectar ao Registry na porta especificada
            Registry registry;
            try {
                registry = LocateRegistry.createRegistry(port); // Cria um novo Registry
                System.out.println("Registry criado na porta: " + port);
            } catch (RemoteException e) {
                // Caso já exista um Registry, conecta-se a ele
                registry = LocateRegistry.getRegistry(port);
                System.out.println("Conectado ao Registry existente na porta: " + port);
            }

            // Registrar o serviço com o nome "index"
            registry.rebind("index", server);
            System.out.println("IndexStorageBarrel registrado com o nome 'index' na porta: " + port);


            // Conectar ao servidor da URLQueue
            Registry registryQueue = LocateRegistry.getRegistry(8184);
            urlQueueInterface = (URLQueueInterface) registryQueue.lookup("URLQueueService");
            System.out.println("Conectado à URLQueue.");

            new Thread(server::listen).start();

            // Adicionar um URL inicial
            // urlQueueInterface.addUrl("https://pt.wikipedia.org/wiki/Wikip%C3%A9dia:P%C3%A1gina_principal");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
