# Android 1.12.32 — recuperação do guia de Esportes

## Causa comprovada e evidência externa

Investigação de 3 de novembro de 2026, por volta de 13h52 (Brasília), com a lista fornecida pelo usuário. Nenhuma URL privada, credencial, identificador de conta ou endereço de reprodução foi incluído neste relatório ou nas fixtures.

| Consulta real | Resultado observado |
| --- | --- |
| M3U | HTTP 200, formato M3U válido, sem endereço XMLTV no cabeçalho |
| Conta do provedor | HTTP 200, autenticada e ativa |
| XMLTV derivado do próprio provedor | HTTP 200, **269 definições de canais e zero programas** |
| API `get_live_streams` | 2.034 canais; IDs numéricos oficiais e `epg_channel_id` disponíveis |
| `get_short_epg`, usando IDs oficiais da ESPN 4/5 | Zero programas |
| `get_simple_data_table`, ESPN 4 | 52 registros; último encerramento em 29/10/2026 às 23h35 no horário informado pela API; nenhum programa atual/futuro |
| `get_simple_data_table`, ESPN 5 | 43 registros; último encerramento em 30/10/2026 às 00h00 no horário informado pela API; nenhum programa atual/futuro |
| Guia brasileiro EPGShare | 266 canais e 16.393 programas; 936 registros esportivos atuais/futuros no filtro de emissoras da investigação |
| Segundo guia consultado apenas para comparação, EPG.pw BR | 319 canais e 21.094 programas; programação esportiva atual disponível |

O desaparecimento não foi causado apenas por apresentação ou placares: **faltou programação atual no provedor**. Isso explica por que versões antigas que funcionavam também deixaram de apresentar partidas. HTTP 200 e uma lista de nomes de canais não comprovam que exista programação utilizável.

A recuperação da 1.12.31 também não cobria essa lista: os streams usam endereços opacos de outro host, sem ID numérico reconhecível. Derivar o ID da URL não funciona nesse caso. Consultar o EPG curto com IDs retirados de tokens seria incorreto; os testes reais acima usaram os IDs oficiais retornados por `get_live_streams`.

## Correções no aplicativo

1. **Inspeção do XMLTV:** contar canais e programas antes dos filtros; distinguir documento vazio, vencido, inválido, falha HTTP/autenticação e falha de rede. Validar a raiz XMLTV e reconhecer gzip pelo conteúdo, com limite de leitura e cancelamento.
2. **API do provedor:** para URLs opacas, resolver o stream pelo nome exato e `epg_channel_id` da API da mesma conta, rejeitando correspondências ambíguas. Consultar a tabela detalhada se o guia curto não fornecer programação utilizável. Percorrer além das primeiras 24 linhas de tabelas cronológicas; descartar horários vencidos sem apresentá-los como atuais.
3. **Recuperação por emissora:** se as fontes do provedor não tiverem programação atual, consultar exclusivamente `https://epgshare01.online/epgshare01/epg_ripper_BR1.xml.gz`. A requisição é fixa e não contém a playlist, credenciais ou URLs dos streams. A associação exige um identificador `.br` de uma emissora esportiva reconhecida e uma correspondência inequívoca no guia. Nome semelhante, grupo "Esportes", time ou placar não estabelecem uma transmissão.
4. **Sem substituir programação atual do provedor:** o guia alternativo não é acionado quando o provedor já tem horários atuais, mesmo se não houver futebol naquele momento. O XMLTV manual continua sendo a primeira escolha. Placares públicos nunca criam canais ou jogos na tela.
5. **Origem identificável:** programação alternativa tem proveniência persistida junto aos metadados do programa. A tela informa EPGShare; Configurações → Programação permite desativá-lo por playlist e consultar o diagnóstico de cada fonte. Desativá-lo também retira seus programas do cache usado pela interface, sem apagar o catálogo.
6. **Identificadores:** diferenças apenas de caixa no mesmo ID XMLTV são aceitas sem substituir IDs conflitantes por nomes parecidos. Uma mudança real de `tvg-id` na M3U passa a atualizar somente o registro afetado; ID vazio recebido posteriormente não apaga um existente.
7. **Reconhecimento:** aceitar um confronto em uma linha curta e inequívoca da descrição quando existe contexto futebolístico; conservar a exclusão de outros esportes/noticiários. `VT` é tratado como reprise, preservando os horários reais do guia.
8. **Concorrência:** uma nova tentativa cancela e aguarda a anterior; respostas e indicadores de carregamento de uma tentativa antiga não sobrescrevem a lista selecionada ou uma tentativa mais recente.

## Arquivos principais

- `Guide.kt`, `GuideDiagnostics.kt`: transporte, inspeção e diagnóstico sem strings de exceções/URLs.
- `XtreamSportsGuide.kt`, `ProviderApi.kt`: descoberta de IDs oficiais, EPG curto e tabela detalhada.
- `BrazilBroadcastGuide.kt`, `SportsGuideRecovery.kt`: recuperação ordenada e associação conservadora do guia alternativo.
- `GuideModels.kt`, `ContentRules.kt`, `SportsModels.kt`, `FootballMetadata.kt`: reconhecimento, IDs e proveniência compatível com os metadados existentes.
- `CatalogDelta.kt`: atualização pontual de `tvg-id`, sem duplicar ou reindexar o catálogo inteiro.
- `AuroraViewModel.kt`, `AppSettings.kt`, `SettingsScreens.kt`, `FootballScreen.kt`: estado, preferência por playlist, diagnóstico e identificação da fonte. O desenho dos cards/ações originais foi preservado.
- Recursos `guide.xml`, testes, fixtures, versão Android, README e notas de publicação.

Não há mudança de versão do SQLite, recriação do banco, alteração da assinatura ou mudança do pacote Android. Metadados antigos sem proveniência continuam legíveis. Os novos textos de diagnóstico têm português, espanhol e inglês; nas demais linguagens há fallback em inglês.

## Validação

As consultas reais acima foram executadas separadamente do aplicativo, sem iniciar reprodução nem alterar dados no provedor. As fixtures preservam os formatos observados, retirando informações privadas e omitindo sinopses extensas; não substituem as evidências das consultas reais.

- `GuideRecoveryRulesTest`: estados vazio/vencido/sem jogos/sem associação, IDs brasileiros, descrição inequívoca, exclusão de NFL, `VT` e atualização pontual de `tvg-id`.
- `SportsGuideRecoveryTest`: XMLTV real sem programas + tabela real vencida + amostra atual do guia alternativo; cards ligados somente aos streams do catálogo, variantes de qualidade, fonte alternativa desativada, preferência por programação atual do provedor, rejeição de canais estrangeiros/parecidos/ambíguos e tabela com jogo após a linha 24.
- Mesmo teste: persistência do guia recuperado e seus metadados após fechar/reabrir o repositório; preservação da contagem e dos favoritos; transporte HTTP local com 401, 503, XML vazio, HTML, XML malformado, dados vencidos e gzip sem cabeçalho de compressão.
- `RecoveredSportsUiTest`: card original com escudos/equipes, botão Assistir, seletor HD/FHD, aviso da fonte e diagnóstico nas configurações.
- Suíte existente: filmes, séries, favoritos, progresso, navegação, player e fechamento forçado com catálogo grande; publicação condicionada ao workflow Android completo aprovado.

A publicação é condicionada à aprovação do workflow Android completo no [PR #48](https://github.com/fabriciomatosqa/iptv-player/pull/48/checks). Os logs definitivos, inclusive a execução na `main`, ficam em [GitHub Actions](https://github.com/fabriciomatosqa/iptv-player/actions/workflows/build-apk.yml). O APK, manifesto e SHA-256 são conferidos novamente após a publicação.

## Limitações e distinções importantes

- O aplicativo não pode corrigir a base de EPG do provedor. Deve continuar mostrando seu estado real; uma correção no APK não transforma dados de outubro em programação de novembro.
- O guia alternativo também é um serviço externo: pode atrasar, falhar ou não cobrir emissoras, PPVs, eventos regionais, países ou canais personalizados. Nesses casos não se inventa uma transmissão, nem se associa um jogo a ESPN/Premiere só porque esse canal existe na playlist.
- O funcionamento da atualização/associação é testável; confirmar que o vídeo de um canal do provedor corresponde ao sinal anunciado exige teste no aparelho. Não foi feita afirmação de ter assistido às transmissões reais da conta do usuário.
- Agenda de transmissão e estado/placar da partida são informações distintas. `VT`/reprise e horários do guia são preservados; os placares não provam que uma transmissão esteja ao vivo.
- A leitura do guia ocorre fora da interface; o cache continua disponível em falhas e o catálogo incremental de filmes/séries não é reimportado para atualizar o EPG.
