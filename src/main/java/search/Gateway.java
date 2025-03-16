package search;

import java.rmi.RemoteException;
import java.rmi.server.UnicastRemoteObject;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.util.*;

public class Gateway extends UnicastRemoteObject implements GatewayInterface {
    //----------------------------------------ATTRIBUTES----------------------------------------

    /** Lista de Storage Barrels ativos */
    private List<IndexStorageBarrelInterface> barrels;

    /** Interface para a URLQueue */
    private URLQueueInterface urlQueue;

    /** Cache para armazenar pesquisas recentes */
    private Map<String, List<String>> searchCache;

    //----------------------------------------CONSTRUCTOR----------------------------------------

    /**
     * Construtor da Gateway. Liga-se aos Storage Barrels e à URLQueue via RMI.
     * @throws RemoteException Caso ocorra um erro na comunicação remota.
     */
    public Gateway() throws RemoteException {
        super();
        barrels = new ArrayList<>();
        searchCache = new HashMap<>();
        connectToServices();
    }

    //----------------------------------------METHODS----------------------------------------

    /**
     * Conecta-se aos Storage Barrels e à URLQueue via RMI.
     */
    private void connectToServices() {
        try {
            Registry registry = LocateRegistry.getRegistry(8183);

            // Procurar todos os Storage Barrels disponíveis
            String[] boundNames = registry.list();
            for (String name : boundNames) {
                // Garantir que só são adicionados IndexStorageBarrels
                if (name.startsWith("index")) barrels.add((IndexStorageBarrelInterface) registry.lookup(name));
            }

            // Conectar à URLQueue
            Registry registryQueue = LocateRegistry.getRegistry(8184);
            urlQueue = (URLQueueInterface) registryQueue.lookup("URLQueueService");

            System.out.println("Gateway conectada a " + barrels.size() + " Storage Barrels e à URLQueue.");
        } catch (Exception e) {
            System.err.println("Erro ao conectar aos serviços RMI: " + e.getMessage());
        }
    }

    /**
     * Pesquisa uma palavra no índice e retorna os URLs onde ela aparece.
     * A pesquisa é distribuída entre os Storage Barrels disponíveis.
     *
     * @param word A palavra a ser pesquisada.
     * @return Lista de URLs contendo a palavra.
     * @throws RemoteException Caso ocorra um erro na comunicação remota.
     */
    @Override
    public List<String> search(String word) throws RemoteException {
        // Verificar se a palavra já está no cache
        if (searchCache.containsKey(word)) {
            System.out.println("Cache hit para a palavra: " + word);
            return searchCache.get(word);
        }

        List<String> results = new ArrayList<>();
        Random random = new Random();

        if (barrels.isEmpty()) {
            System.err.println("Nenhum Storage Barrel disponível!");
            return results;
        }

        // Escolher um Storage Barrel aleatório para distribuir a carga
        IndexStorageBarrelInterface selectedBarrel = barrels.get(random.nextInt(barrels.size()));

        try {
            results = selectedBarrel.searchWord(word);
            searchCache.put(word, results); // Adicionar ao cache
        } catch (RemoteException e) {
            System.err.println("Erro ao pesquisar num Storage Barrel. Tentando outro...");
            barrels.remove(selectedBarrel); // Remover o Storage Barrel que falhou
            if (!barrels.isEmpty()) {
                return search(word); // Tentar novamente com outro Storage Barrel
            }
        }

        return results;
    }

    /**
     * Adiciona um novo URL à fila para ser indexado.
     *
     * @param url O URL a ser indexado.
     * @throws RemoteException Caso ocorra um erro de comunicação remota.
     */
    @Override
    public void addUrl(String url) throws RemoteException {
        if (urlQueue != null) {
            urlQueue.addUrl(url);
        } else {
            System.err.println("Erro: URLQueue não está disponível...");
        }
    }

    //----------------------------------------MAIN----------------------------------------

    /**
     * Inicia o servidor da Gateway e regista-o no serviço RMI.
     * @param args Argumentos da linha de comando.
     */
    public static void main(String[] args) {
        try {
            Gateway gateway = new Gateway();
            Registry registry = LocateRegistry.createRegistry(8185);
            registry.rebind("GatewayService", gateway);
            System.out.println("Gateway ativa e pronta para receber pedidos...");
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }
}
