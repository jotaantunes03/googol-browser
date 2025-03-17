package search;
import java.rmi.Remote;
import java.rmi.RemoteException;

public interface URLQueueInterface extends Remote {

    /**
     * Adiciona um novo URL à Queue.
     * @param url Url a ser inserido.
     * @throws RemoteException Se ocorrer um erro de comunicação remota.
     */
    void addUrl(String url) throws RemoteException;

    /**
     * Obtém o próximo URL da Queue para ser processado.
     *
     * @return O próximo URL na fila ou null se a fila estiver vazia.
     * @throws RemoteException Se ocorrer um erro de comunicação remota.
     */
    String takeUrl() throws RemoteException;

    /**
     * Verifica se a fila de URLs está vazia.
     *
     * @return <b>True</b> se a fila de URLs estiver vazia.
     * <b>False</b> se contiver um ou mais URLs.
     * @throws RemoteException Se ocorrer um erro de comunicação remota.
     */
    boolean isEmpty() throws RemoteException;

}
