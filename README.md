# AURORA IPTV

Aplicativo IPTV para Android 7+ com identidade visual inspirada nas auroras boreais.

## Versão 1.12.5

- **Reprodução:** mensagens mais humanas para HTTP 404 (endereço ausente), outras respostas do servidor, rede, formato e codec. O código técnico permanece visível em uma linha discreta para diagnóstico. Nenhum erro HTTP é tratado como prova de que todo o aplicativo está correto.
- **Futebol:** nova aba **AO VIVO** antes de Jogos mostra somente transmissões em andamento confirmadas pelo placar recente ou sinalizadas como ao vivo pelo guia.
- Reprises, partidas com placar final e transmissões encerradas ocupam cards compactos; tocar neles expande placar, data, canal e botão Assistir.
- Detalhes de gols e cartões são solicitados somente ao expandir partidas com identificação confiável na fonte de placares. Há cache local, sem consultas massivas. Se os dados não estiverem disponíveis, a interface informa isso claramente.
- Mantém o limite de remoção de jogos encerrados há mais de uma hora, filtros, favoritos, navegação e atualização online, com traduções nos 20 idiomas.

## Versão 1.12.4

- Mostra detalhes técnicos seguros para erros de reprodução, incluindo classe/código Media3, status HTTP e codec/resolução quando disponíveis.
- Não expõe URLs ou credenciais de playlist. O diagnóstico desaparece ao tentar novamente, trocar o vídeo ou fechá-lo.
- Permite investigar problemas 4K e Dolby Vision sem alterar buffers, decodificadores ou fluxos que já funcionam. **Não corrige automaticamente formatos incompatíveis nem erros do provedor.**

## Versão 1.12.3

- Subcategorias de **Ao Vivo** mostram mini logos reais das emissoras quando há uma imagem compatível na playlist M3U. A busca é limitada, feita fora da interface e reutiliza o cache de imagens existente; grupos sem logo confiável ficam apenas com o nome.
- Removido o losango vermelho (♦️) dos nomes exibidos nas subcategorias e da linha secundária dos cards, sem alterar o identificador do grupo, os filtros, os canais e os favoritos.
- Atualização incremental compatível com a versão anterior, preservando catálogo, listas, favoritos e reprodução.

## Versão 1.12.2

Completa o reconhecimento dos identificadores de 64 caracteres URL-safe, incluindo letras maiúsculas/minúsculas, dígitos, hífen e sublinhado. Esse é o formato presente em todas as 315.906 entradas da lista analisada. A migração para o banco v9 corrige tanto as bases v7 quanto as v8 da 1.12.1, consolidando os mesmos identificadores entre servidores e mantendo favoritos, progresso e registro diário. Os testes grandes usam tokens desse formato, além dos testes para tokens hexadecimais, formatos distintos e URLs comuns.

## Versão 1.12.1

Corrige o crescimento indevido do catálogo quando o provedor alterna os servidores dos endereços `/play/<identificador de 64 dígitos hexadecimais>`. A identidade fica vinculada à playlist e ao caminho completo, incluindo formato, query e fragmento; outros endereços continuam separados pela URL integral. O endereço de reprodução é atualizado sem inserir outra cópia. A migração do banco v7 para v8 remove cópias acumuladas, preserva a ordem original e o endereço mais recente, remapeia favoritos, último conteúdo e progresso e recalcula os totais. Séries favoritas, listas e registro diário são mantidos. O banco reparado é compactado quando possível; a limpeza não depende de um novo download da lista.

## Versão 1.12.0

- Futebol com nova apresentação Aurora: cards com bordas e luzes suaves em verde, ciano e violeta, escudos maiores, nomes abaixo dos escudos e placares centralizados com melhor contraste.
- Transmissão em um painel próprio com calendário, dia completo, horário em `HH:mm` e indicação de ao vivo ou reprise. Botão **Assistir** destaca o canal, mantendo as transmissões alternativas e o retorno ao futebol.
- Busca e campeonatos com visual renovado e contagem dos jogos filtrados. Em paisagem, busca no cabeçalho e confronto ao lado da transmissão aproveitam o espaço horizontal; fontes ampliadas reorganizam os times e o placar.
- Escudo vetorial com iniciais quando a imagem não carrega. As iniciais desaparecem ao carregar um escudo real, sem sobreposição em imagens transparentes. Temas claro e escuro preservam as cores da Aurora.
- Disponível pelo atualizador online da versão 1.11, com a mesma assinatura, preservando listas e favoritos.

## Versão 1.11.0

- **Configurações → Atualizar online** verifica as versões Android estáveis do GitHub, compara o código da versão instalada e informa se há atualização. Versões de outras plataformas, rascunhos e prévias são ignoradas.
- Baixa o APK dentro do aplicativo com porcentagem, verifica tamanho, SHA-256, pacote, versão, compatibilidade e assinatura antes de abrir o instalador oficial. Uma autorização do Android pode ser necessária; a instalação sempre depende da confirmação do usuário.
- Mantém listas, catálogo, favoritos e posições de reprodução. Cancelar a instalação permite tentar novamente com o arquivo já verificado; arquivos incompletos não são instalados. Erros de rede e limites do GitHub têm mensagem e nova tentativa.
- Builds aprovados na branch principal publicam um Release `android-vX.Y.Z` com `aurora-iptv.apk`, `aurora-update.json` e SHA-256. O Release só aparece para o app depois de todos os arquivos serem enviados. A publicação rejeita qualquer mudança na assinatura existente.

Instale a versão 1.11 uma vez sobre a atual para habilitar esse recurso. Depois, use **Atualizar online**. Download: [versão Android 1.11.0](https://github.com/fabriciomatosqa/iptv-player/releases/tag/android-v1.11.0).

## Versão 1.10.0

- Início ajustado ao espaço da tela, com o último conteúdo no topo e o total abaixo de Futebol. A reprodução em andamento continua na prévia; celulares muito pequenos ou com fonte ampliada mantêm a rolagem necessária para acessibilidade.
- Temas claro, escuro e conforme o sistema em Configurações → Aparência, com cores da aurora boreal, contraste próprio para cada tema e barras do Android compatíveis.
- Várias listas em Configurações → Minha playlist, com cadastro, edição de nome e seleção da lista ativa. Cada lista mantém seu catálogo, atualização diária, guia, últimos conteúdos, séries favoritas e posição de reprodução.
- Favoritos de filmes disponíveis na lista e junto ao vídeo; estrelas nos cartazes e na página da série guardam a série inteira, com todas as temporadas.
- Horários de futebol em `HH:mm`, como `11:00`. Migração preserva a lista já cadastrada, catálogo e favoritos.

## Versão 1.9.0

- Horários de futebol em `HH:mm`. Cards permanecem por uma hora após o fim da transmissão e desaparecem automaticamente, incluindo ao reabrir a agenda. Uma partida que continua ao vivo não expira durante a prorrogação.
- Quando os dois times e o campeonato coincidem com um placar atualizado, uma data divergente no guia é corrigida pelo início informado pelo serviço de resultados. A correção fica salva; ao observar o encerramento de uma partida acompanhada ao vivo, a hora desse encerramento passa a orientar a remoção. Reprises identificadas no guia mantêm a própria programação.
- Favoritos de séries guardam o título inteiro, com estrela no cartaz e na página da série. Favoritos exibe uma entrada por série; abrir essa entrada libera todas as temporadas e episódios. Episódios anteriormente favoritados são convertidos sem alterar os favoritos de filmes e canais.
- Filmes e episódios retomam automaticamente a posição salva por conteúdo e playlist. A posição é gravada periodicamente e ao pausar, trocar de vídeo ou sair do app; conteúdo concluído começa novamente. Canais ao vivo continuam na transmissão atual.
- Migração aditiva do banco, sem apagar playlist ou catálogo. Testes cobrem favoritos, horários conflitantes, expiração, reprodução real, fechamento e retomada.

## Recursos da versão 1.8.0

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

O APK está em **Releases → AURORA IPTV (Android) → aurora-iptv.apk**, além dos artefatos em Actions. O pacote `br.com.fabriciomatos.iptvplayer` e a assinatura foram preservados para instalação sobre versões anteriores. Publicar uma atualização exige aumentar `versionCode` e `versionName` em `app/build.gradle.kts` e atualizar `release/notes.md`.

A chave original é recuperada do cache `aurora-test-signing-key-v2`; o secret opcional `AURORA_KEYSTORE_BASE64` permite restaurar exatamente o mesmo keystore se o cache for removido. Sem a chave original, a compilação para e pede restauração, evitando publicar um APK incompatível. Nenhuma chave, token de GitHub ou credencial de playlist é incluída no aplicativo.
