# Guia de Execução do Projeto Googol

## Pré-requisitos
- **WSL** instalado e configurado
- **Java** instalado
- Dependências incluídas na pasta `lib/`
- Script `build.sh` disponível na raiz do projeto

## Passos para executar o projeto
Cada componente do projeto deve ser iniciado numa janela separada do terminal. Segue as instruções abaixo para cada um:

### 1. Iniciar a `UrlQueue`
```sh
wsl
cd /caminho/do/projeto
./build.sh
cd target/
java -cp "./lib/jsoup-1.18.3.jar:./lib/sqlite-jdbc-3.49.1.0.jar:." search.URLQueue
```

### 2. Iniciar os Servidores
#### Servidor 1
```sh
wsl
cd /caminho/do/projeto
cd target/
java -cp "./lib/jsoup-1.18.3.jar:./lib/sqlite-jdbc-3.49.1.0.jar:." search.IndexStorageBarrel 8182 server1
```

#### Servidor 2
```sh
wsl
cd /caminho/do/projeto
cd target/
java -cp "./lib/jsoup-1.18.3.jar:./lib/sqlite-jdbc-3.49.1.0.jar:." search.IndexStorageBarrel 8183 server2
```

### 3. Iniciar o `Gateway`
```sh
wsl
cd /caminho/do/projeto
cd target/
java -cp "./lib/jsoup-1.18.3.jar:." search.Gateway
```

### 4. Iniciar o `Googol`
```sh
wsl
cd /caminho/do/projeto
cd target/
java -cp "./lib/jsoup-1.18.3.jar:." search.GoogolClient
```

### 5. Iniciar o `Downloader`
```sh
wsl
cd /caminho/do/projeto
cd target/
java -cp "./lib/jsoup-1.18.3.jar:." search.Downloader
```

## Notas
- **Cada comando deve ser executado numa janela separada do terminal**.
- **Certifica-te de que todas as dependências estão na pasta `lib/` antes de correr o projeto**.
- **Se houver erros, verifica se o `build.sh` foi executado corretamente**.

---
Se houver dúvidas ou problemas na execução, verifica a estrutura dos ficheiros e as permissões do `build.sh`. Boa execução!

