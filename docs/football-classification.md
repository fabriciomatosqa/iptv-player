# Futebol: evidências, identidade e filtros

`Programme` guarda o título recebido e `FootballMetadata` guarda separadamente título original,
subtítulo, descrição, competição, gênero, país, divisão e esporte. Nenhuma limpeza do nome
exibido altera esses campos. Os canais continuam sendo os objetos originais da playlist.

`footballDetails` interpreta os prefixos e sufixos antes de limpar os times. Consulta competição
explícita, campos originais do EPG, categoria, grupo da playlist e nome do canal. Marcadores como
feminino/feminina, femenina, women, female e ladies identificam o gênero; a identidade de um clube
nunca o determina. Uma competição explícita tem precedência sobre grupos genéricos. Evidências
conflitantes não permitem associar placares masculinos. Campos insuficientes ficam desconhecidos.

`FootballCompetitions.kt` centraliza aliases completos, identificadores, grupo e prioridade.
Uma correspondência parcial não pode transformar Série C em Série A, Championship em Premier
League ou uma liga de outro país na inglesa. Gênero e categoria de base integram a identidade do
confronto, independentemente do filtro visual. O acesso a placares e escudos respeita a competição
compatível; falta de cobertura feminina não autoriza usar resultados masculinos.

Todas as partidas femininas compartilham o filtro `__women__` (Jogos Femininos), mas mantêm a
competição no card e na identidade usada para agrupar canais. Ao Vivo e Jogos reutilizam os
mesmos registros. O identificador antigo da aba Libertadores Feminina continua aceito.

## Prioridades e descoberta

Ao Vivo e Jogos são sempre os dois primeiros filtros. As competições presentes são ordenadas
por grupo, prioridade, nome normalizado e identificador, sem depender de placares ou da ordem do
EPG. Os grupos são: Brasil/competições sul-americanas, feminino/seleções, principais ligas
europeias, outras competições europeias e demais países.

Para ajustar prioridades conhecidas, altere a tabela `footballCompetitionRules`. Chamadores
também podem passar um mapa de `FootballCategoryPriority` para `footballTabs`, sem deslocar os
dois filtros gerais. Uma competição nova recebida em um campo explícito ou identificada em um
título estruturado ganha categoria automaticamente; não precisa estar na tabela. Países europeus
identificados entram no grupo europeu, e os demais recebem o grupo padrão. Sem prioridade
específica, o nome determina uma ordem estável. Não são criadas abas vazias para campeonatos.

## Guia existente e atualizações

O banco v10 acrescenta somente uma coluna de metadados ao guia e invalida sua data de atualização.
O leitor reprocessa títulos e categorias antigos, inclusive confrontos antes não marcados como
futebol. Catálogo, favoritos, URLs, horários, imagens e informações de reprodução são preservados.

Ao atualizar o EPG, evidências de gênero são conservadas somente para o mesmo canal, horário
e par exato de equipes, sem uma nova competição conflitante. Correções explícitas do provedor
têm precedência. A interface recebe os dados já conciliados e mantém busca, filtro e retorno
do player. Se o provedor antigo já tiver eliminado toda evidência, o aplicativo precisa de uma
atualização com metadados suficientes; não reclassifica o jogo por conhecer os clubes.

## Verificações e publicação

`FootballClassificationTest` cobre classificação, aliases, divisões, países, descoberta, prioridades,
filtros e atualização de evidências. `FootballMetadataAndroidTest` verifica XMLTV, persistência,
reabertura, atualização e migração v9; `FootballWomenUiTest` verifica aba agregadora e busca.
Os testes existentes cobrem agrupamento de canais, placares, reprises, detalhes, navegação,
reprodução e catálogo grande.

O workflow existente compila o APK, verifica a assinatura original e executa testes unitários,
lint, compilação dos testes de interface, testes de metadados de release e verificações no emulador.
O job de publicação depende de `build` e `verify`, inclusive em execução manual. A versão deve
ser incrementada antes do envio final para `main`; não há aprovação manual adicional para publicar.
