package search;

import java.rmi.Remote;
import java.rmi.RemoteException;
import java.util.List;

public interface GatewayInterface extends Remote {

    /**
     * Pesquisa uma palavra no índice e retorna os URLs onde ela aparece.
     *
     * @param word A palavra a ser pesquisada.
     * @return Lista de URLs contendo a palavra.
     * @throws RemoteException Caso ocorra um erro de comunicação remota.
     */
    List<String> search(String word) throws RemoteException;

    /**
     * Adiciona um novo URL à fila para ser indexado.
     *
     * @param url O URL a ser indexado.
     * @throws RemoteException Caso ocorra um erro de comunicação remota.
     */
    void addUrl(String url) throws RemoteException;
}
