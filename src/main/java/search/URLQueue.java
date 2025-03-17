package search;

import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;
import java.sql.*;
import java.util.LinkedList;
import java.util.Queue;

/**
 * Classe URLQueue que implementa a interface remota URLQueueInterface.
 *
 * Esta classe gere uma fila de URLs utilizando uma base de dados SQLite para
 * persistência, garantindo escalabilidade e integridade dos dados, mesmo em
 * ambientes multi-thread.
 */
public class URLQueue extends UnicastRemoteObject implements URLQueueInterface, AutoCloseable {

    //---------------------------------------- ATRIBUTOS ----------------------------------------

    /** Ligação à base de dados SQLite */
    private Connection connection;

    /** Fila em memória para gestão rápida de URLs */
    private Queue<String> queue;

    //---------------------------------------- CONSTRUTOR ----------------------------------------

    /**
     * Construtor da classe URLQueue.
     *
     * Inicializa a base de dados SQLite e configura a tabela necessária para
     * armazenar os URLs. Além disso, carrega os URLs existentes para a memória.
     *
     * @throws RemoteException Se ocorrer um erro na comunicação RMI.
     */
    public URLQueue() throws RemoteException {
        super();
        queue = new LinkedList<>();
        setupDatabase();
    }

    //---------------------------------------- MÉTODOS ----------------------------------------

    /**
     * Configura a base de dados SQLite, criando a tabela necessária caso ainda não exista.
     */
    private void setupDatabase() {
        try {
            connection = DriverManager.getConnection("jdbc:sqlite:../urlqueue.db");

            // Definir modos de escrita e sincronização para evitar corrupção
            try (Statement stmt = connection.createStatement()) {
                stmt.execute("PRAGMA journal_mode=WAL;");
                stmt.execute("PRAGMA synchronous=NORMAL;");
                stmt.execute("CREATE TABLE IF NOT EXISTS urls (id INTEGER PRIMARY KEY AUTOINCREMENT, url TEXT UNIQUE)");
            }

            System.out.println("Base de dados da URLQueue inicializada com sucesso.");
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    /**
     * Adiciona um URL à fila e armazena-o na base de dados.
     *
     * @param url O URL a ser adicionado.
     * @throws RemoteException Se ocorrer um erro de comunicação RMI.
     */
    @Override
    public synchronized void addUrl(String url) throws RemoteException {
        if (!queue.contains(url)) {
            queue.add(url);
            try (PreparedStatement stmt = connection.prepareStatement("INSERT OR IGNORE INTO urls (url) VALUES (?)")) {
                stmt.setString(1, url);
                stmt.executeUpdate();
                System.out.println("URL adicionado: " + url);
            } catch (SQLException e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * Retira um URL da fila e remove-o da base de dados.
     *
     * Se a fila estiver vazia, tenta buscar um URL diretamente da base de dados.
     *
     * @return O próximo URL da fila ou null se não existirem URLs disponíveis.
     * @throws RemoteException Se ocorrer um erro de comunicação RMI.
     */
    @Override
    public synchronized String takeUrl() throws RemoteException {
        String url = queue.poll();

        if (url == null) {
            // Buscar da base de dados se a fila estiver vazia
            try (Statement stmt = connection.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT url FROM urls LIMIT 1")) {

                if (rs.next()) {
                    url = rs.getString("url");
                }
            } catch (SQLException e) {
                e.printStackTrace();
                return null;
            }
        }

        if (url != null) {
            removeFromDatabase(url);
        }

        return url;
    }

    /**
     * Remove um URL da base de dados de forma segura.
     *
     * @param url O URL a ser removido.
     */
    private synchronized void removeFromDatabase(String url) {
        try {
            connection.setAutoCommit(false);

            try (PreparedStatement deleteStmt = connection.prepareStatement("DELETE FROM urls WHERE url = ?")) {
                deleteStmt.setString(1, url);
                int affectedRows = deleteStmt.executeUpdate();

                if (affectedRows > 0) {
                    connection.commit();
                    System.out.println("URL removido da base de dados: " + url);
                } else {
                    connection.rollback();
                }
            }
        } catch (SQLException e) {
            rollbackTransaction();
            e.printStackTrace();
        } finally {
            restoreAutoCommit();
        }
    }

    /**
     * Reverte uma transação ativa se o autoCommit estiver desativado.
     */
    private synchronized void rollbackTransaction() {
        try {
            if (!connection.getAutoCommit()) {
                connection.rollback();
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    /**
     * Restaura o autoCommit da base de dados após uma operação.
     */
    private synchronized void restoreAutoCommit() {
        try {
            if (!connection.getAutoCommit()) {
                connection.setAutoCommit(true);
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    /**
     * Verifica se a fila de URLs está vazia.
     *
     * @return true se estiver vazia, false caso contrário.
     * @throws RemoteException Se ocorrer um erro de comunicação RMI.
     */
    @Override
    public synchronized boolean isEmpty() throws RemoteException {
        return queue.isEmpty();
    }

    /**
     * Fecha a conexão com a base de dados quando a classe for destruída.
     */
    @Override
    public void close() {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
                System.out.println("Conexão com a base de dados encerrada corretamente.");
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    //---------------------------------------- MAIN ----------------------------------------

    /**
     * Método principal para iniciar o servidor da URLQueue.
     *
     * @param args Argumentos da linha de comando.
     */
    public static void main(String args[]) {
        try (URLQueue urlQueue = new URLQueue()) { // Usa try-with-resources para garantir o fecho da conexão
            Registry registry = LocateRegistry.createRegistry(8184);
            registry.rebind("URLQueueService", urlQueue);
            System.out.println("URLQueueService pronto e a aguardar pedidos...");

            // Mantém o serviço em execução
            synchronized (urlQueue) {
                urlQueue.wait();
            }

        } catch (RemoteException | InterruptedException e) {
            e.printStackTrace();
        }
    }
}
