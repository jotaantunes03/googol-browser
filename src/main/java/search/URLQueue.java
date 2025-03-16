package search;

import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;
import java.sql.*;
import java.util.LinkedList;
import java.util.Queue;

public class URLQueue extends UnicastRemoteObject implements URLQueueInterface {
    private Connection connection;
    private Queue<String> queue;

    public URLQueue() throws RemoteException {
        super();
        queue = new LinkedList<>();

        try {
            // Conectar à base de dados SQLite
            connection = DriverManager.getConnection("jdbc:sqlite:../urlqueue.db");
            Statement stmt = connection.createStatement();
            // Criar a tabela se não existir
            stmt.execute("CREATE TABLE IF NOT EXISTS urls (id INTEGER PRIMARY KEY AUTOINCREMENT, url TEXT UNIQUE)");

            System.out.println("Base de dados pronta a utilizar...");
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    @Override
    public synchronized void addUrl(String url) throws RemoteException {
        if (!queue.contains(url)) {  // Evita URLs duplicados
            queue.add(url);
            try (PreparedStatement stmt = connection.prepareStatement("INSERT OR IGNORE INTO urls (url) VALUES (?)")) {
                stmt.setString(1, url);
                stmt.executeUpdate();
            } catch (SQLException e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    public synchronized String takeUrl() throws RemoteException {
        String url = queue.poll();  // Remove da memória
        if (url != null) {
            // URL foi removido da fila, então removê-lo da base de dados também
            try (PreparedStatement deleteStmt = connection.prepareStatement("DELETE FROM urls WHERE url = ?")) {
                // Iniciar uma transação
                connection.setAutoCommit(false);

                deleteStmt.setString(1, url);
                deleteStmt.executeUpdate();

                // Confirmar a transação
                connection.commit();
            } catch (SQLException e) {
                // Reverter a transação em caso de erro
                if (connection != null) {
                    try {
                        connection.rollback();
                    } catch (SQLException ex) {
                        ex.printStackTrace();
                    }
                }
                e.printStackTrace();
            }
        } else {
            try (Statement stmt = connection.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT url FROM urls LIMIT 1")) {
                if (rs.next()) {
                    url = rs.getString("url");

                    try (PreparedStatement deleteStmt = connection.prepareStatement("DELETE FROM urls WHERE url = ?")) {
                        // Iniciar uma transação
                        connection.setAutoCommit(false);

                        deleteStmt.setString(1, url);
                        deleteStmt.executeUpdate();

                        // Confirmar a transação
                        connection.commit();
                    } catch (SQLException e) {
                        // Reverter a transação em caso de erro
                        if (connection != null) {
                            try {
                                connection.rollback();
                            } catch (SQLException ex) {
                                ex.printStackTrace();
                            }
                        }
                        e.printStackTrace();
                    }
                }
            } catch (SQLException e) {
                e.printStackTrace();
            }
        }
        return url;
    }


    @Override
    public synchronized boolean isEmpty() throws RemoteException {
        return queue.isEmpty();
    }


    //----------------------------------------MAIN----------------------------------------
    public static void main(String args[]) {

        try {
            URLQueue urlQueue = new URLQueue();
            Registry registry = LocateRegistry.createRegistry(8184);
            registry.rebind("URLQueueService", urlQueue);
            System.out.println("URLQueueService ready...");


        } catch (RemoteException e) {
            e.printStackTrace();
        }


    }
}
