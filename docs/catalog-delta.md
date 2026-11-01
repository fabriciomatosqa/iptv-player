# Atualização incremental do catálogo

O download calcula uma impressão digital SHA-256 dos bytes e do endereço final
usado para resolver links relativos. Se ela coincide com a última importação
confirmada, uma resposta HTTP 200 idêntica também dispensa parsing, comparação
por registro e gravação do catálogo. A referência é persistida na mesma transação
dos conteúdos; um download sem importação ou uma falha não avança essa referência.
Os validadores HTTP continuam sendo atualizados. Não há mudança de esquema.

Quando o arquivo muda, a M3U recebida é lida em fluxo. Cada lote de até 400 registros consulta o índice de
identidade da playlist. Conteúdos iguais ficam intocados: não executam INSERT,
INSERT OR IGNORE nem UPDATE na tabela de canais. Entradas novas são inseridas;
endereços, cabeçalhos e metadados realmente alterados são atualizados para manter
a reprodução funcionando. A identidade estável continua reconhecendo trocas de CDN.

Exemplo: a série salva tem episódios 1 a 15; a nova resposta contém 1 a 17. Apenas
16 e 17 são inseridos, e só o diretório dessa série e sua categoria são recalculados.
Os demais episódios, séries, categorias, favoritos e posições de reprodução permanecem.
Se a resposta seguinte é igual, não há gravações nas tabelas do catálogo nem reconstrução
dos índices. Reordenar a lista também não altera conteúdos iguais. A atualização dos validadores HTTP e do registro diário continua normal.

Os contadores são incrementados pelas diferenças confirmadas. A reconstrução geral
é reservada à primeira carga, a índices ainda não preparados e a alterações amplas
que afetam mais de 256 séries/categorias. As estruturas temporárias de comparação
ficam limitadas ao lote; não é criada outra cópia permanente do catálogo nem uma
coleção de centenas de milhares de objetos na memória. Não há mudança de esquema.

A importação continua transacional. Uma falha reverte tanto conteúdos quanto índices,
contadores e validadores. Respostas parciais preservam itens antigos. Endereços salvos
em favoritos e progresso continuam resolvidos pela identidade do conteúdo. Os testes
de reabertura e force-stop existentes permanecem obrigatórios.

O rótulo Comparando catálogo descreve a leitura e verificação, não uma regravação geral.
Ao concluir, a interface mostra as novidades ou informa que não há conteúdo novo.
Quando os dados de reprodução mudaram, informa que o catálogo foi atualizado.
Servidores que não oferecem ETag/Last-Modified ou uma API de diferenças ainda exigem
baixar e ler a M3U para detectar novidades. O catálogo salvo continua disponível durante
a atualização em segundo plano, respeitando a política de uma tentativa automática por dia.

## Verificação

CatalogDeltaTest audita comandos SQLite com triggers. A regressão 15 → 17 exige
exatamente duas inserções, zero atualizações dos conteúdos antigos e apenas uma série
reindexada, mesmo com outras 1.201 séries salvas. Uma repetição idêntica exige zero
escritas no catálogo. Um trigger BEFORE INSERT rejeita tentativas de inserir conteúdos
já existentes, mesmo com OR IGNORE. Também são verificados rollback, troca de CDN sem
reindexação, reclassificação, múltiplas temporadas e equivalência com uma carga integral.
O servidor HTTP local testa respostas 200 iguais após reabertura, mudanças de ETag
e rollback antes de confirmar a impressão digital. Não usa credenciais reais.
