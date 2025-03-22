package search;

import java.rmi.RemoteException;
import java.rmi.server.UnicastRemoteObject;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.util.*;

public class Gateway extends UnicastRemoteObject implements GatewayInterface {
    //----------------------------------------ATTRIBUTES----------------------------------------

    /** Lista de Storage Barrels ativos */
    private Map<IndexStorageBarrelInterface, Integer> barrelsLoad;

    /** Interface para a URLQueue */
    private URLQueueInterface urlQueue;

    /** Cache para armazenar pesquisas recentes */
    private Map<String, List<String>> searchCache;

    private final Random random = new Random();


    //----------------------------------------CONSTRUCTOR----------------------------------------

    /**
     * Construtor da Gateway. Liga-se aos Storage Barrels e à URLQueue via RMI.
     * @throws RemoteException Caso ocorra um erro na comunicação remota.
     */
    public Gateway() throws RemoteException {
        super();
        barrelsLoad = new HashMap<>();
        searchCache = new HashMap<>();
        connectToServices();
    }

    //----------------------------------------METHODS----------------------------------------

    /**
     * Conecta-se aos Storage Barrels e à URLQueue via RMI.
     */
    private void connectToServices() {
        try {

            // Iterar por um intervalo de portas para conectar aos IndexStorageBarrels
            for (int port = 8182; port <= 8183; port++) {
                try {
                    // Conecta-se ao Registry na porta especificada
                    Registry registry = LocateRegistry.getRegistry(port);
                    System.out.println("Conectado ao Registry na porta: " + port);

                    // Assumindo que o objeto remoto está registrado anonimamente na porta
                    IndexStorageBarrelInterface barrel = (IndexStorageBarrelInterface) registry.lookup("index");
                    barrelsLoad.put(barrel, 0); // Adicionar o barrel à lista
                    System.out.println("Conectado a um Storage Barrel na porta " + port);
                } catch (Exception e) {
                    // Capturar falhas de conexão para portas sem serviços disponíveis
                    System.out.println("Nenhum serviço disponível na porta: " + port);
                }
            }


            // Conectar à URLQueue
            Registry registryQueue = LocateRegistry.getRegistry(8184);
            urlQueue = (URLQueueInterface) registryQueue.lookup("URLQueueService");

            System.out.println("Gateway conectada a " + barrelsLoad.size() + " Storage Barrels e à URLQueue.");
        } catch (Exception e) {
            System.err.println("Erro ao conectar aos serviços RMI: " + e.getMessage());
        }
    }

    /**
     * Seleciona o Storage Barrel mais livre.
     * Se não houver, retorna null.
     */
    private IndexStorageBarrelInterface getLeastLoadedBarrel() {
        return barrelsLoad.entrySet()
                .stream()
                .min(Comparator.comparingInt(Map.Entry::getValue))  // Menor carga
                .map(Map.Entry::getKey)
                .orElse(null);
    }

    /**
     * Seleciona um Storage Barrel de forma aleatória.
     */
    private IndexStorageBarrelInterface getRandomBarrel() {
        List<IndexStorageBarrelInterface> barrels = new ArrayList<>(barrelsLoad.keySet());
        return barrels.get(random.nextInt(barrels.size()));
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

        if (barrelsLoad.isEmpty()) {
            System.err.println("Nenhum Storage Barrel disponível!");
            return results;
        }

        // Selecionar o barrel (inicialmente aleatório)
        IndexStorageBarrelInterface selectedBarrel = getRandomBarrel();

        if (selectedBarrel == null) {
            System.err.println("Nenhum Storage Barrel disponível!");
            return results;
        }

        // Incrementar a carga antes de iniciar a pesquisa
        barrelsLoad.put(selectedBarrel, barrelsLoad.get(selectedBarrel) + 1);

        try {
            results = selectedBarrel.searchWord(word);
            searchCache.put(word, results); // Adicionar ao cache
        } catch (RemoteException e) {
            System.err.println("Erro ao pesquisar num Storage Barrel. Tentando outro...");
            barrelsLoad.remove(selectedBarrel); // Remover o que falhou

            if (!barrelsLoad.isEmpty()) {
                return search(word); // Tentar novamente com outro Storage Barrel
            }
        } finally {
            // Decrementar a carga após a pesquisa (sucesso ou falha)
            if (barrelsLoad.containsKey(selectedBarrel)) {
                barrelsLoad.put(selectedBarrel, barrelsLoad.get(selectedBarrel) - 1);
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
