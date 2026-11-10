AURORA IPTV Android 1.12.39 — sincronização otimizada para catálogos grandes e dispositivos de pouca memória

- Faz a verificação HTTP da playlist antes de carregar metadados do provedor; respostas 304 e catálogos idênticos evitam a consulta de séries e a reconciliação integral desnecessária.
- Em listas alteradas, lê do SQLite apenas identificador, assinatura compacta e estado de cada registro; carrega os muitos campos antigos somente para itens efetivamente diferentes.
- Preserva a comparação completa para registros migrados ou com assinaturas diferentes, impedindo perda de mudanças nas URLs, legendas, headers, arte, tipo de conteúdo, episódios ou classificação parental.
- Inclui migração de banco aditiva (v11) e gradual: a abertura do catálogo não reescreve milhões de registros para calcular assinaturas.
- Mantém atualização incremental, cache de catálogo, telas paginadas, acesso à reprodução durante atualização, listas múltiplas, escudos, esportes, séries, filmes, favoritos, progresso, identidade e assinatura original.
- Mantém confirmação transacional do catálogo, proteção contra arquivos parciais e recuperação após force-stop.

A comparação de listas remotas M3U ainda precisa ler os novos bytes quando o provedor não fornece um serviço incremental. Os resultados de benchmarks do Android não equivalem a medições em equipamentos físicos de 2 GB de RAM.
