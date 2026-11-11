# Central Esportiva Aurora

O EPG associado aos canais da playlist ativa determina a disponibilidade de transmissão.
Placares, escudos e classificações são complementos. Uma resposta esportiva externa não cria
um card, um canal, um horário nem uma indicação de reprise.

## Diagnóstico do código anterior

Esta investigação usou o código e dados sintéticos. Não houve acesso a uma playlist privada
nem reprodução de transmissões reais do usuário, e não foi capturado um encerramento por
falta de memória em um aparelho. Os pontos abaixo são problemas demonstráveis do fluxo,
não uma atribuição definitiva da causa de um crash específico.

- A inclusão de partidas de temporada em `footballListings` **já estava bloqueada na base
  recebida**. Havia código de apresentação de fixtures sem stream sem uso, além de uma aba
  de Brasileirão forçada mesmo quando não havia EPG. Essa apresentação sem uso foi removida;
  os filtros deixaram de forçar o campeonato.
- `FootballScoreRepository` ainda consultava uma semana histórica, dias adicionais e a
  programação de todos os cards de futebol, publicando no estado da interface os jogos de
  outras ligas devolvidos pelas respostas diárias. O escopo agora é o dos cards visíveis.
- `sportsEvents` acrescentava associações pelo nome mesmo quando o canal tinha um `tvg-id`
  conflitante. Diferentemente do futebol, também perdia os intervalos individuais de cada canal.
- Outros esportes eram consolidados sem verificar sobreposição das faixas ou conflitos de
  horários no mesmo stream. Categorias femininas, de base e divisões também precisavam de
  identidade própria fora do futebol.
- A modalidade Futebol, Automobilismo e a categoria F1 eram impostas pela tela. A nova
  hierarquia de transmissões usa apenas modalidades e campeonatos efetivamente identificados.
- Um resultado final antigo podia ser associado ao mesmo par de equipes até sete dias antes
  e transformar uma faixa futura em reprise. Agora esse histórico exige reprise explícita;
  transmissões comuns exigem início compatível, com diferença máxima de 90 minutos.
- O reconhecimento de automobilismo aceitava noticiários com uma menção a F1. A recuperação
  do guia contava apenas futebol, podendo consultar novamente a API mesmo com NBA/NHL atuais.
- Chamadas de escudos eram iniciadas em filhos do `viewModelScope`; o desaparecimento do card
  não cancelava esses filhos. Agora elas acompanham a corrotina do card e o processamento de
  catálogos de escudos ocorre em IO.
- A conciliação do guia comparava cada registro anterior com todos os registros novos e
  mantinha apenas um dia futuro. Ela agora indexa as substituições por canal e preserva os
  eventos válidos na janela de sete dias, sem conservar intervalos contraditos pelo EPG novo.

## Navegação e apresentação

`Esportes → modalidade → campeonato → eventos por data → canais → player existente`.

- Apenas modalidades/campeonatos com eventos no período aparecem na lista de transmissões.
  Esportes adicionais podem vir do campo estruturado `sport` do EPG, com identificação concreta
  do confronto ou sessão. Há reconhecimento adicional de boxe, MMA, rugby, futsal, ciclismo,
  atletismo, natação, críquete, golfe e surfe.
- Busca por modalidade, campeonato, participante, evento, sessão e canal. Filtros Todas,
  Ao Vivo e Em breve acompanham a navegação e não criam categorias vazias.
- Campeonatos femininos, masculinos, de base e divisões continuam separados quando há
  evidência suficiente. No futebol, a nova tela apresenta os campeonatos individualmente,
  inclusive os femininos; os identificadores/filtros antigos permanecem nas funções de
  compatibilidade e nos dados existentes.
- Datas completas, Hoje/Amanhã, ordem cronológica e cabeçalhos de etapa no automobilismo.
  O usuário pode alternar entre o fuso do aparelho e `America/Sao_Paulo`. A escolha fica nas
  preferências existentes, sem alterar timestamps nem exigir migração de banco.
- Cards preservam cores/temas Aurora, escudos confiáveis ou iniciais, logos disponíveis,
  ações de reprodução e o seletor de canais com qualidade e horário individual.
- Em paisagem, busca e cabeçalho compartilham espaço; listas e itens são virtuais. Rotação,
  retorno do player e troca de playlist preservam ou reinicializam o estado pertinente.
- F1 continua com resumo, pilotos, construtores, próxima corrida, calendário e estatísticas.
  Sem transmissão F1, a página permanece acessível por um botão explicitamente complementar
  na Central; não se cria uma modalidade/campeonato vazio para disponibilizar esse conteúdo.

## Evidência, identidade e estados

1. `SportsChannelIndex` reutiliza a resolução de IDs/aliases inequívocos de `Guide.channelId`.
   IDs conflitantes nunca são substituídos por um nome semelhante. Canais adultos e VOD
   não entram no índice. O catálogo consultado é exclusivamente o da playlist ativa.
2. Candidatos XMLTV são filtrados pelos canais solicitados antes de entrar na agenda. O guia
   normal continua disponível; títulos incompletos não viram partidas fictícias.
3. A janela da Central é de sete dias futuros e até uma hora após o fim da faixa. Intervalos
   inválidos ou superiores a 48 horas não viram eventos. O guia salvo
   continua sendo a fonte reutilizada; abrir a Central não reimporta M3U/filmes/séries.
4. O agrupamento considera modalidade, competição, participantes normalizados, categoria e
   reprise. A diferença de até 90 minutos permite horários de pré-transmissão entre emissoras,
   mas também exige intervalos sobrepostos. Dois horários conflitantes no mesmo stream não
   são fundidos. Todos os objetos/URLs/qualidades originais dos canais são preservados.
5. Cada canal conserva seu `Programme`. O fim do intervalo é exclusivo: um canal futuro ou
   encerrado não herda o estado Ao Vivo de outra emissora do mesmo card.
6. Ao Vivo exige uma faixa EPG atual e evidência explícita ou placar fresco compatível.
   Reprise depende de sinal explícito do guia, inclusive título/subtítulo. Resultado final,
   nome de canal ou uma exibição anterior, isoladamente, não comprovam reprise/transmissão.
7. Adiado/cancelado só é mostrado quando há resultado associado confiável. Placar e estado
   da partida não deslocam nem prolongam os horários da transmissão.
8. Automobilismo usa etapa e sessão, sem inventar equipes: treinos numerados, classificação,
   sprint, corrida e corridas numeradas conservam identidades distintas. Sessões/etapas
   insuficientemente descritas usam o título original para evitar fusões especulativas.

## Desempenho e controle de recursos

- Construção/normalização da agenda e projeções de busca/filtro em `Dispatchers.Default`.
  Resolução de imagens e armazenamento em IO. Nenhuma nova biblioteca foi adicionada.
- Índice de canais compartilhado na construção da agenda e substituições do guia indexadas
  por canal; regex de qualidade reutilizadas. Não há busca de todos os canais por evento.
- Dados de busca pré-calculados; navegação consulta a modalidade/competição selecionada.
  Um índice de linhas alimenta `LazyColumn` com chaves e tipos de conteúdo estáveis. Imagens
  e detalhes só são solicitados para cards compostos/expandidos.
- Placares: até 24 cards, quatro datas UTC e quatro ligas por carga, concorrência máxima três,
  respostas limitadas a 4 MiB e prazo de carga de 40 s, com timeouts HTTP existentes.
  A API diária ainda pode devolver partidas de outras ligas; elas são descartadas antes de
  publicar o feed no ViewModel. Não há requisição de temporada.
- O estado conserva até 128 resultados vinculáveis ao EPG, preferindo observações recentes,
  para não perder complementos ao navegar. O cache persistente diário tem limite de 48
  entradas. Cache existente continua disponível em falhas; uma resposta inválida não o apaga.
- Atualização de placares a cada dois minutos apenas nos eventos de futebol visíveis, com
  app em primeiro plano, sem configurações/player sobre a Central. Mudança de interesse e
  saída da tela cancelam a operação anterior, inclusive atualização manual. Não há fila de
  atualizações manuais destacada da tela.
- Respostas antigas verificam novamente playlist e referências do estado antes de publicar.
  A troca de playlist limpa navegação/agenda/placares transitórios; não apaga dados persistidos.
- O relógio visual avança a cada 30 s enquanto a Central está ativa. A posição da lista só é
  atualizada após mudar de item ou deslocar pelo menos 48 px.
- Cache de guia continua respeitando seis horas. Recuperação de programação ausente em uma
  nova abertura tem intervalo mínimo de quinze minutos; tentativa manual permanece disponível.

## Validação local

Comando, usando o ambiente preparado no workspace oficial:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\.local-dev\gradle.ps1 --no-daemon --console=plain :app:testDebugUnitTest :app:lintDebug :app:compileDebugSources :app:compileDebugAndroidTestKotlin
```

Rodada final: **BUILD SUCCESSFUL em 1m31s**, com resultados em
`.local-dev/sports-validation.log` e resumo em `.local-dev/sports-validation-summary.json`.

| Verificação | Resultado executado |
| --- | --- |
| `:app:testDebugUnitTest` | 153 testes, 25 suítes, zero falhas, erros ou testes ignorados |
| `:app:lintDebug` | Zero erros; 160 avisos e sete informações, sem desabilitar verificações |
| `:app:compileDebugSources` | Fontes/recursos Debug compilados com sucesso |
| `:app:compileDebugAndroidTestKotlin` | Testes Android compilados com sucesso; não executados |
| Recursos localizados | 20 arquivos, mesmas 21 chaves, placeholder de contagem e Unicode conferidos |
| Revisão Git inicial | `git diff --check` sem problemas; 59 arquivos de implementação/documentação, antes da autorização de envio |

O aviso da ferramenta local sobre XML do SDK 4 versus leitor XML 3 do AGP continua
presente, sem impedir as verificações. As versões das ferramentas do projeto foram preservadas.

Os testes unitários incluem 21 novos cenários em `SportsHubRulesTest`: catálogo sem esporte,
hierarquia, variantes, IDs conflitantes, ambiguidades, nomes semelhantes, categorias, reprise,
EPG inválido/vencido, sessões F1/F2, modalidades dinâmicas, playlist ativa/adultos, busca/filtros,
fusos, ausência de geração por calendários públicos, escopo de placares, catálogo grande,
respostas parciais, recuperação de outros esportes e atualização correta de resultados.

O teste sintético de escala cria 100.001 conteúdos e 20.001 registros de EPG. Ele verifica que
apenas o evento com canal válido é retido: **312 ms na última execução**. Seu tempo é medido na JVM do computador;
isso não é um perfil Android e não comprova estabilidade ou consumo máximo em 2 GB de RAM.

`SportsHubUiTest` acrescenta um fluxo instrumentado com mídias sintéticas e prévia em
segundo plano: modalidade →
campeonato → canais HD/FHD → reprodução → retorno com busca → paisagem/tema claro → troca
de playlist → verificação do catálogo, favoritos e guia salvos. Testes existentes de futebol,
feminino, programação recuperada e F1 foram adaptados à nova hierarquia/regras de evidência.

## Limites e pendências

- Não há aparelho conectado nem emulador configurado no ambiente local. Execução instrumentada,
  inspeção visual, reprodução real, rolagem e perfil de memória em aparelho de cerca de 2 GB
  continuam pendentes. Compilar testes Android não equivale a executá-los.
- A chave original `.signing/aurora-debug.keystore` está indisponível. `assembleDebug` e a
  validação de instalação sobre a versão anterior dependem de restaurá-la. Não foi criada
  outra chave nem removida/alterada a configuração de assinatura para produzir APK.
- EPG indisponível/incompleto, sessões sem identificação, ausência de logo, falta de cobertura
  ou falha de API mantêm estados/elementos sem confirmação. Não se preenchem lacunas com
  calendários externos. O EPGShare continua opcional e sujeito a cobertura/atrasos próprios.
- Gênero/base/divisão sem metadados suficientes não são deduzidos pela identidade dos clubes.
  Escudos que poderiam confundir categoria ficam em fallback.
- A reformulação não alterou schema, banco, pacote, assinatura, contrato do atualizador ou workflow.
  A etapa inicial foi entregue sem alteração de versão, commit, push ou publicação.
- A base local é `bed8ae16a1d5494c336ff9e42738c9ba07f0a551`. No início da tarefa,
  `origin/main` tinha o release `1b7285f82788a48642673c10b207991172219f6b`, que altera apenas
  versão/notas. Ele foi consultado por fetch e mantido separado na etapa inicial.

## Preparação do envio autorizado

Após a revisão local, o usuário autorizou o envio para `main`. O release remoto 1.12.40
foi integrado por fast-forward, verificando que os 59 arquivos locais permaneciam idênticos.
Para a publicação automática foi preparada a versão **1.12.41, versionCode 62** e atualizadas
as notas em `release/notes.md`. A chave original e as verificações do workflow permanecem
obrigatórias; publicação de APK depende do sucesso de build, verificação e assinatura no CI.
O conjunto para envio contém 61 arquivos de projeto alterados/novos.
Após integrar a base e atualizar a versão, a validação local foi repetida com sucesso
em 1m28s: 153 testes unitários, lint e compilação das fontes Debug/testes Android.
Os dois testes Python dos metadados de release também passaram.

## Correções após o primeiro verify no CI

O [primeiro envio da 1.12.41](https://github.com/fabriciomatosqa/iptv-player/actions/runs/42947050610)
compilou e passou nos testes JVM/lint, mas revelou três falhas instrumentadas:

- `FootballUiTest` consultava o placar da reprise antes da carga assíncrona dos cards visíveis.
  O teste agora aguarda o resultado correspondente e o nó de placar antes de conferir os gols.
- `SportsHubUiTest` encontrava o mesmo nome no botão do card e no seletor aberto. As asserções
  agora verificam os canais dentro do seletor. Rotação/tema também aguardam o conteúdo visível.
- `XtreamSportsGuideTest` esperava uma única consulta para variantes com IDs EPG diferentes.
  A expectativa foi corrigida para confirmar os dois streams independentemente. Novos cenários
  verificam o reaproveitamento quando o ID é o mesmo e a separação quando os programas diferem.

Também foi investigado o `OutOfMemoryError` do benchmark na execução anterior da main
([1.12.40](https://github.com/fabriciomatosqa/iptv-player/actions/runs/42939130355)). O stack trace
mostrou `PlaylistRepository.liveChannels` chamado por `AuroraViewModel.refreshGuide`, durante
o carregamento de todos os canais ao vivo de um catálogo local sem EPG configurado.
Agora, quando não há endereço nem guia salvo, a consulta SQL seleciona candidatos ao guia
alternativo e aplica a identificação conservadora já existente. Sem alternativa habilitada,
não hidrata canais desnecessariamente. Não existe limite que corte variantes válidas.
Leituras de canais propagam cancelamento e fecham o cursor ao interromper. A leitura de um
guia configurado/salvo permanece disponível, sem alterar banco, dados ou assinatura.

`GuideChannelSelectionTest` cobre seleção, IDs estrangeiros/ambíguos, conteúdo adulto,
preservação de catálogo/favoritos e cancelamento da leitura. A matriz completa de benchmark
e os requisitos de publicação do workflow permanecem inalterados. A versão continua 1.12.41
(62), pois a tentativa anterior não publicou um release.

## Arquivos

Os arquivos de produção ficam em `app/src/main/java/br/com/fabriciomatos/iptvplayer/`:

A relação completa de caminhos está em `.local-dev/sports-changed-files.txt`.

| Área | Arquivos |
| --- | --- |
| Nova agenda, hierarquia, linhas e evidência | `SportsSchedule.kt`, `SportsScoreScope.kt`, `SportsHubScreen.kt` |
| Estado, ciclo de vida, escudos e preferências | `AuroraViewModel.kt`, `ArtworkResolver.kt`, `AppSettings.kt` |
| Identificação, canais e placares | `SportsModels.kt`, `GuideModels.kt`, `ContentRules.kt`, `FootballScores.kt`, `FootballFormatting.kt`, `FootballScoreRepository.kt`, `FootballSerieA.kt` |
| Leitura e recuperação de programação | `Guide.kt`, `SportsGuideRecovery.kt`, `XtreamSportsGuide.kt`, `BrazilBroadcastGuide.kt` |
| Cards e campeonato F1 | `FootballScreen.kt`, `MotorsportCard.kt`, `MotorsportEvents.kt`, `MotorsportChampionshipScreen.kt` |
| Textos | `app/src/main/res/values/sports.xml` e `sports.xml` nas 19 pastas de idiomas existentes |
| Testes JVM | `SportsHubRulesTest.kt`, `FootballScoresTest.kt`, `FootballBroadcasterDiagnosisTest.kt`, `FootballSerieATest.kt` |
| Testes Android | `SportsHubUiTest.kt`, `SportsUiNavigation.kt`, `FootballUiTest.kt`, `FootballWomenUiTest.kt`, `FootballTimingTest.kt`, `FootballScoreAndroidTest.kt`, `ScoreSourcesTest.kt`, `MotorsportChampionshipUiTest.kt`, `RecoveredSportsUiTest.kt`, `SerieAFixturesUiTest.kt`, `AuroraUiTest.kt` |
| Documentação | `README.md`, `docs/sports-central.md`, `docs/motorsport-championship.md` |
| Preparação da publicação autorizada | `app/build.gradle.kts`, `release/notes.md` |
