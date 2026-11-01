# Android Smartphone — catálogo disponível antes da sincronização

Escopo: APK `br.com.fabriciomatos.iptvplayer`, base Android 1.12.28
(`ef5fe30ceb49fd98b741abece72a2fb8108381bf`). A versão Desktop e a interface
LG/Tizen não fazem parte desta alteração.

## Comparação dos pedidos e reaproveitamento

Os três pedidos convergem em disponibilizar o catálogo persistido antes de
downloads, comparações ou manutenção global. O terceiro define a ordem:
concluir o smartphone, depois adaptar Android TV/Google TV e validar seu APK.

O trabalho anterior não alterou nem compilou o Android nesta conversa. A main
1.12.28 já tem SQLite/WAL, parser em streaming, paginação, diretório persistido
de séries, ETag/Last-Modified/304, hash de corpo e comparação incremental.
Esses recursos foram preservados. O CI anterior da própria main foi aprovado
(execução 41267579099); isso não mede automaticamente abertura e responsividade.

A correção de índices da staging Desktop não foi copiada para Android: o
esquema e as consultas são diferentes. Reaproveitou-se o método de diagnóstico,
a separação entre disponibilidade local e atualização e a auditoria de dados.

## Problemas e alteração

- A inicialização chamava restore, que conta todos os canais e examina as
  classificações pendentes mesmo quando os agregados estão prontos.
- O guia inteiro era recuperado antes de initialized; agora o trabalho ocorre
  no carregador assíncrono do guia depois da disponibilização do catálogo.
- A atualização manual ativava loading mesmo com conteúdo salvo. Agora somente
  uma biblioteca vazia ativa esse estado; o conteúdo existente continua acessível.
- A migração de favoritos de episódios fazia junções globais por URL. Agora
  consulta por favorito e playlist usando a chave primária existente.

`openCatalog` consulta o marcador transacional indexed e o total persistido.
Se estiverem prontos, não executa a recuperação global nem escreve favoritos.
Caso contrário, continua usando restore. SQLiteOpenHelper mantém as migrações
de esquema obrigatórias; a migração antiga do controle parental invalida o
marcador para não expor dados ainda sem classificação. Não há limpeza de banco,
troca de identidade, relaxamento de synchronous=FULL ou mudança de assinatura.

O download negocia gzip e mantém o hash do conteúdo descomprimido. A contagem de
Content-Length agora usa bytes codificados para também detectar respostas gzip
incompletas; EOF/CRC e a confirmação de arquivo pronto continuam obrigatórios.
O teste HTTP compara as duas codificações da mesma lista, valida 304, hash e
preservação do catálogo após truncamento do trailer ou do corpo declarado.

A manutenção de favoritos ocorre depois da abertura e não substitui alterações
do usuário que já estejam pendentes. O diretório de séries e a paginação já
existentes continuam carregando episódios somente ao abrir a série/temporada.

## Evidência inicial, não confundir com benchmark do APK

`test/benchmark-startup-sql.py` reproduz as consultas em SQLite no computador.
Com 1,2 milhão de linhas: COUNT por playlist 72 ms, consulta de classificações
pendentes 530 ms, consulta do resumo por chave primária 0,40 ms. Uma execução,
esquema reduzido, sem interface Android. EXPLAIN mostra varredura dos registros
da playlist no caminho antigo e busca de uma linha na tabela de resumos no novo.
Esses números justificam testar o novo caminho, mas não são tempos de abertura
nem garantias de ganho em celulares.

## Validação Android preparada

- CatalogStartupTest: abertura pronta somente de leitura, recuperação de índice
  incompleto/controle parental e leitura concorrente durante uma importação.
- CacheFirstPlaybackTest: canal de vídeo real salvo toca enquanto uma resposta
  HTTP de atualização fica deliberadamente retida; o teste exige que loading
  permaneça falso e que o vídeo comece antes de liberar a resposta.
- CatalogStartupPerformanceTest: matriz opt-in com 10k, 100k, 300k, 600k e 1,2M;
  importação inicial, restore legado versus abertura pronta, primeiras páginas,
  inicialização da Activity/navegação visível, reimportação idêntica, dois
  episódios adicionais e troca de uma URL. Verifica totais, episódios, favorito,
  último conteúdo e progresso. Os resultados têm tempo de parede, CPU, PSS
  amostrado ao final das etapas e armazenamento incluindo WAL/SHM.
- O script existente de verificação continua executando todos os testes e a
  prova de fechamento forçado. Depois executa a matriz separadamente e preserva
  JSON/logs em app/build/aurora-ui, no artefato aurora-ui-verification existente.

O restore legado continua disponível para um comparativo pareado no mesmo APK.
As reaberturas do repositório não são partidas a frio do processo; a Activity é
medida separadamente e o teste existente de force-stop continua independente.
PSS amostrado não é pico exato de memória. Fontes sintéticas locais isolam o
custo do catálogo; a prova HTTP mede separadamente a independência da rede.

## Critérios e limites

Exigir dados íntegros, leitura e reprodução antes da resposta HTTP, ausência de
trabalho global no caminho de um resumo pronto e primeiras páginas independentes
da conclusão da importação. Metas propostas: Activity utilizável até 2 s e
primeira página local até 500 ms no ambiente de referência, com repetições antes
de transformá-las em garantia. Não deduzir desempenho de uma compilação aprovada.

Ainda é necessário registrar os resultados reais do CI e diferenças por hardware.
Não há benchmark controlado dos concorrentes nem celular/TV físico conectado.
Arquivos M3U modificados ainda precisam ser transferidos e interpretados quando
o servidor não oferece um delta; preservar duas gravações novas não elimina esse
custo. APIs Xtream adicionais exigem validação própria e não foram adicionadas
a este primeiro conjunto de mudanças. A redução de bytes do teste gzip não
constitui garantia de latência em redes ou provedores diferentes.

Esta branch não altera main, versionCode/versionName nem os contratos de
atualização online. O APK do CI é um candidato para revisão com a identidade
existente; sua geração não constitui publicação de um novo release oficial.
