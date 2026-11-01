AURORA IPTV Android 1.12.28 — Comparação e gravação incremental do catálogo.

- Uma lista byte a byte igual à última importação é reconhecida por SHA-256, mesmo quando o servidor responde HTTP 200; dispensa nova importação.
- Compara a lista recebida em lotes pequenos com o catálogo salvo. Conteúdos iguais não são enviados novamente aos comandos de inserção ou atualização.
- Se uma série tinha 15 episódios e recebeu o 16 e o 17, grava apenas os dois novos e atualiza o índice dessa série e sua categoria.
- Não reconstrói todas as séries e categorias ao encontrar poucas novidades. Endereços e informações de reprodução realmente alterados continuam sendo atualizados sem duplicar conteúdos.
- A tela informa Comparando catálogo durante a leitura e, ao concluir, mostra quantos conteúdos novos foram adicionados ou que não há novidades.
- Mantém o catálogo anterior em respostas parciais ou falhas. Listas, favoritos, progresso, controle parental, assinatura e atualização online são preservados.
- Quando o provedor não oferece uma resposta de lista inalterada, ainda é necessário ler a M3U completa para descobrir as diferenças; isso não significa regravar os conteúdos antigos.
