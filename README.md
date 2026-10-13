# AURORA IPTV

Aplicativo IPTV para Android 7+ com identidade visual inspirada nas auroras boreais.

## Versão 1.8.0

- Placares e tempo de jogo consultados no FotMob, com ESPN como alternativa. Corrigidas as consultas por intervalo de datas que retornavam HTTP 400; as requisições usam dias individuais, cache e limite de tempo. Uma falha em um arquivo histórico não marca todos os campeonatos como indisponíveis.
- Transmissões em andamento mostram AO VIVO com indicador piscando na cor de destaque do app. As demais mostram o dia da semana por extenso, data e horário. Reprises conservam a identificação e o resultado final quando disponível; uma transmissão de reprise em andamento não recebe o indicador ao vivo.
- Botão Sinopse nos filmes, na página da série, nos episódios e ao lado do vídeo. As sinopses vêm da API do próprio provedor, incluindo o resumo específico de cada episódio quando informado, e ficam salvas para consulta sem conexão. O índice de filmes aceita URLs de reprodução opacas e distingue títulos com anos diferentes.
- Novos textos traduzidos nos 20 idiomas e migração que preserva catálogo, lista, favoritos e guia. Validação inclui conectividade real dos placares no Android, sinopses em tela e reabertura sem conexão.

## Recursos da versão 1.7.0

- Futebol com placar e tempo informados pelo serviço público da ESPN. Atualização a cada 60 segundos enquanto a agenda está visível e o app está em primeiro plano; consultas simultâneas limitadas e cache separado para resultados e calendário.
- O horário real da partida é associado aos dois times e ao campeonato antes de mostrar o placar. Partidas encerradas antes da faixa de transmissão aparecem como reprise, com resultado final; o XMLTV também pode informar transmissão ao vivo ou reprise. Falta de cobertura e dados desatualizados são indicados, sem inventar placares ou tempos.
- Busca por time, nomes abaixo dos escudos e abas na ordem Jogos, FIFA, Brasileirão, Libertadores, Sul-americana e demais campeonatos disponíveis.
- Abrir um canal pela agenda e voltar pelo player ou pelo Android retorna ao futebol, preservando o filtro, a busca e a posição. Os 20 idiomas incluem os novos textos.
- Migração do guia para guardar as indicações de transmissão sem apagar catálogo, endereço da lista ou favoritos.

## Recursos da versão 1.6.0

- Jogos entre seleções mostram o logo da FIFA e os escudos das federações, incluindo CBF, AFA, US Soccer e RFEF. Nomes femininos e de base usam a mesma federação; os escudos de outros países são identificados por nome exato e guardados em cache.
- Voltar de um filme ou dos episódios restaura a subcategoria, a busca e a posição anterior da lista. O retorno funciona pelos botões da interface e pelo botão Voltar do Android, inclusive com catálogos paginados.

## Recursos da versão 1.5.0

- Atualização com porcentagem baseada nos bytes recebidos e importados, também no topo da tela inicial durante a atualização automática, mantendo o catálogo disponível. Os 100% só aparecem após a gravação completa no SQLite; quando o servidor não informa o tamanho do download, aparece o volume recebido até ser possível calcular a porcentagem da importação.
- Controles do vídeo visíveis por 3 segundos, com desaparecimento gradual até 5 segundos. Um toque volta a mostrá-los. Áudio e legendas disponíveis no título aparecem abaixo do tempo, com seleção efetiva das faixas.
- Cartazes de anime recuperados pelo título no catálogo Kitsu, com correspondência de nomes, proteção contra imagens de outra série e cache para reabertura. A imagem do provedor recebe uma alternativa quando falha.
- Categorias equivalentes de filmes reunidas sem excluir conteúdos. Ordem: Adicionado recentemente, Top Lançamentos 2026, 4K, demais categorias em ordem alfabética e +18 por último. Recentes reúne o grupo Update e os filmes novos recebidos nas atualizações dos últimos 30 dias, sem tratar o catálogo inicial inteiro como novo.
- Futebol mostra apenas confrontos com os dois times identificados. Cada cartão separa campeonato, times, horário e canal. Escudos são consultados pelo nome exato no catálogo da competição e ficam em cache; nomes ambíguos usam iniciais até haver identificação segura. Os horários e canais continuam vindo do guia da playlist.

## Recursos preservados

- Cartão **Futebol** na tela inicial com os jogos atuais e futuros informados pelo guia dos canais da playlist. O toque abre um canal disponível; transmissões alternativas aparecem no menu do jogo. A agenda e o guia ficam salvos localmente.
- Guia XMLTV automático para provedores compatíveis, com endereço alternativo em **Configurações → Programação**. Horários e cobertura dependem do guia fornecido, sem inventar jogos ou canais.
- Conteúdo adulto separado em **Conteúdo +18**, no fim das categorias. A primeira abertura exige cadastrar uma senha; busca, favoritos, reprodução e últimos conteúdos respeitam o bloqueio. Sair do app bloqueia a sessão novamente. Alterar a senha exige a atual.
- Senha armazenada como hash com salt aleatório e PBKDF2; tentativas incorretas repetidas têm espera de 30 segundos.
- **Configurações → Idioma** com 20 idiomas e traduções para toda a interface, incluindo suporte a escrita da direita para a esquerda. Os títulos dos conteúdos continuam na língua da playlist.
- Associação segura de cartazes com títulos que incluem ano, correções para cartazes conhecidos de anime e placeholder para imagens de anime sem identificação confiável.

Idiomas: português brasileiro, inglês, espanhol, francês, alemão, italiano, neerlandês, polonês, russo, ucraniano, árabe, hindi, bengali, chinês simplificado, chinês tradicional, japonês, coreano, turco, vietnamita e indonésio.

## Catálogo e reprodução

- Importação de M3U em fluxo, diretamente para SQLite; download temporário validado e recuperável antes da importação. Catálogos grandes não ficam inteiros na memória.
- Navegação paginada, totais calculados no banco e busca em todo o catálogo, inclusive conteúdo fora das páginas já abertas.
- Categorias e cartazes consultados na API do mesmo provedor quando disponível. URLs opacas e grupos ambíguos passam a respeitar a classificação informada pelo servidor.
- Cartaz da série aplicado também nos episódios; programação XMLTV reservada aos canais ao vivo.
- Teste de escala com 315.898 conteúdos únicos, 43.792 filmes, 270.072 episódios e 2.034 canais, incluindo encerramentos reais do processo e reabertura.


- Abertura vertical com cartões Ao vivo, Filmes e Séries; abertura horizontal com painel de conteúdo e atalhos.
- Navegação em paisagem com menu, categorias, vídeo e guia Agora/A seguir. Tela cheia disponível no player.
- **Minha playlist** permite cadastrar e editar o nome e o endereço M3U. O endereço é salvo antes do carregamento, inclusive quando o servidor está indisponível.
- Logo original de 1536 × 1536 px dentro do app, ícone de 512 px e ícone adaptativo com área de proteção.
- Ao vivo, Filmes e Séries possuem catálogos separados. Séries exibem um cartaz por título; temporadas e episódios aparecem depois de selecionar a série.
- Catálogo persistente em SQLite, salvo em transação antes de aparecer na tela. A migração recupera o cache da versão anterior e seu backup mesmo quando a preferência auxiliar está incorreta.
- Uma tentativa automática na primeira abertura de cada dia, pelo calendário local do aparelho. O catálogo salvo aparece primeiro; a atualização acontece em segundo plano, sem indicador de carregamento.
- Atualizações acrescentam URLs novas, preservando todos os conteúdos antigos, mesmo que o servidor deixe de enviá-los. Falhas de conexão não apagam o catálogo. A atualização manual continua disponível.
- Atualização condicional HTTP (ETag/Last-Modified) quando oferecida pelo servidor, sem reiniciar o vídeo.
- Busca sem distinção de acentos e filtros fora da thread da interface; listas desenhadas sob demanda.
- Favoritos e último conteúdo salvos no aparelho.
- Reprodução HLS, DASH e arquivos diretos compatíveis com o dispositivo; tratamento de falhas, nova tentativa e fallback de decodificador.
- EPG XMLTV/XMLTV.gz quando a M3U informa `url-tvg` ou `x-tvg-url`. Sem EPG, os canais continuam acessíveis e a interface informa a ausência de programação.

## Como usar

1. Abra **Minha playlist** e cole a URL M3U fornecida pelo seu provedor.
2. Toque em **Salvar playlist**. Não será preciso cadastrar novamente ao reabrir.
3. Escolha Ao vivo, Filmes ou Séries. Em Séries, selecione o cartaz, a temporada e o episódio.
4. Use o botão de tela cheia no vídeo ou volte ao início para navegar. A rotação mantém o player.

O aplicativo não inclui credenciais, canais ou uma assinatura de conteúdo. A disponibilidade de cada transmissão depende do servidor e do formato de mídia.

## Compilação e verificações

JDK 17, Gradle 8.9 e SDK Android 35:

```sh
gradle testDebugUnitTest lintDebug assembleDebug
```

O GitHub Actions executa testes de classificação, séries e calendário, testes de banco/migração/rollback, navegação e reprodução HTTP em um emulador. A verificação também encerra o processo com `am force-stop` duas vezes e confirma que o catálogo e os favoritos reaparecem.

O APK fica em **Actions → Build AURORA IPTV APK → execução concluída → Artifacts → aurora-iptv-apk**. O pacote `br.com.fabriciomatos.iptvplayer` e a chave de teste em cache foram preservados para instalação sobre as versões anteriores.
