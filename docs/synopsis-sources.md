# Sinopses, dados do provedor e fallback público (Android)

## Ordem de busca

1. O AURORA prioriza o endpoint Xtream Codes `player_api.php?action=get_vod_info` ou `get_series_info` associado à playlist fornecida pelo usuário. Metadados em `info`, `movie_data` e no nível raiz são reconhecidos.
2. Os resultados são armazenados em `artwork_cache` e reutilizados mesmo após novas instalações/aberturas do app, sem migrar nem recriar o banco.
3. Quando o provedor não tem uma sinopse legível, não disponibiliza API, não retorna ID da mídia ou responde com erro, o aplicativo pode pesquisar somente o nome público do título (e ano, se identificado):
   - **Séries:** API pública [TVmaze](https://www.tvmaze.com/api) (sinopse, gêneros, ano, elenco e imagens, com atribuição).
   - **Filmes:** API de busca da [Wikipédia](https://www.mediawiki.org/wiki/API:Search) em português e, caso não haja correspondência, inglês.
4. Um resultado externo só é exibido se título e ano forem compatíveis. Em caso de ambiguidades, prefere-se indicar indisponibilidade a vincular o enredo de outra obra.
5. Campos do provedor **sempre prevalecem** sobre dados externos. A tela de detalhes informa a fonte externa e oferece um link para atribuição.
6. Consultas acontecem sob demanda quando o usuário abre os detalhes, **não** durante a importação de playlists e **não** para todos os itens do catálogo.
7. Cache externo positivo: 21 dias; sem correspondência: 6 horas; erros temporários não apagam dados anteriores. Recarregar forçado ignora o cache fresco.
8. Não há API key embutida. Consultas externas usam apenas título e ano; **nunca** enviam URL de playlist, usuário, senha, endereço de reprodução, favoritos, EPG ou dados pessoais.
9. Se a fonte externa estiver indisponível, a navegação, reprodução, favoritos, banco de dados e atualização do app permanecem funcionais.

## Observações e limites

- A cobertura das fontes públicas não é completa, especialmente para conteúdos regionais, lançamentos e nomes alternativos.
- Wikipedia e TVmaze podem retornar sinopses em inglês. Não atribuir correspondências aproximadas sem verificação.
- Elenco/fotos são exibidos somente quando a fonte realmente dispõe desses registros.
- Textos da Wikipedia e TVmaze exigem atribuição; manter a referência clicável quando usados.
- Respeitar limites de uso dos serviços, identificar o cliente com User-Agent e evitar consultas repetidas.
