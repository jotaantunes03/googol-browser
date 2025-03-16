package search;

import java.rmi.*;
import java.util.*;

public interface IndexStorageBarrelInterface extends Remote {

    /**
     * Adiciona uma palavra ao índice invertido, associando-a a um URL.
     * Os URLs são armazenados como uma string separada por `;` na base de dados.
     *
     * @param word A palavra a ser indexada.
     * @param url O URL onde a palavra foi encontrada.
     * @throws RemoteException Caso ocorra um erro na operação remota.
     */
    public void addToIndex(String word, String url) throws RemoteException;


    /**
     * Realiza uma pesquisa no índice invertido e retorna os URLs associados a uma palavra.
     *
     * @param word A palavra a ser pesquisada.
     * @return Lista de URLs que contêm a palavra pesquisada.
     * @throws RemoteException Caso ocorra um erro na operação remota.
     */
    public List<String> searchWord(String word) throws RemoteException;


    /**
     * Adiciona uma ligação entre duas páginas Web no grafo de ligações.
     *
     * @param sourceUrl O URL da página de origem.
     * @param linkedUrl O URL da página de destino.
     * @throws RemoteException Caso ocorra um erro na operação remota.
     */
    public void addLink(String sourceUrl, String linkedUrl) throws RemoteException;



    boolean isUrlIndexed(String url) throws RemoteException;


}
