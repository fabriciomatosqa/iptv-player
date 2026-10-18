# Página do campeonato de Automobilismo

Navegação: Esportes → Automobilismo → Fórmula 1 → Página do Campeonato.
A Fórmula 1 permanece acessível mesmo sem eventos no EPG. Voltar (inclusive pelo Android)
retorna à mesma competição e às transmissões existentes.

As seis abas mostram resumo, pilotos, construtores, próxima corrida, calendário e estatísticas.
As tabelas têm rolagem horizontal; o calendário carrega resultados somente ao expandir uma etapa.
Os modelos de apresentação são reutilizáveis. A integração é específica para F1; nenhuma regra
de pontuação é aplicada a F2, F3, IndyCar, WEC ou a outras modalidades.

## Fonte e atualização

- API pública [Jolpica-F1 / Ergast](https://github.com/jolpica/jolpica-f1/blob/main/docs/README.md),
  sem chave. HTTPS, identificação do aplicativo e limites de resposta/timeout.
- Consultas fixadas no ano do aparelho: calendário, classificações de pilotos/construtores
  e resultados das posições 1, 2 e 3. Cada resposta tem limite de 100 registros;
  respostas truncadas são rejeitadas. Os três filtros de posição evitam baixar todos os resultados.
- Carregamento somente ao abrir a página. Cache de seis horas no armazenamento de cache existente,
  retenção de 30 dias para uso offline e espera de 15 minutos após falha, inclusive ao reabrir.
  Requisições sequenciais, espaçadas em pelo menos 300 ms, sem polling.
- Resultados completos de uma etapa são consultados apenas ao expandir seu card.
- URLs, credenciais e nomes de canais nunca são enviados para a API de campeonato.

## Limites dos dados

- Classificação e vitórias vêm da fonte, sem recalcular pontos pelas transmissões.
- Pódios só são exibidos quando as três respostas cobrem as mesmas etapas e são consistentes
  com a rodada e a soma das vitórias da classificação. Caso contrário, ficam indisponíveis.
- Uma etapa só aparece finalizada com resultado de vencedor publicado. Datas passadas, sozinhas,
  não comprovam conclusão, cancelamento, adiamento ou sessão em andamento.
- A API de calendário não fornece todos esses estados nem horário de término das sessões;
  a interface apresenta situação não informada quando falta confirmação.
- Datas com horário UTC são convertidas pelos formatadores existentes para o fuso do aparelho.
  Datas sem horário permanecem datas, sem inventar meia-noite.
- Transmissões vinculadas são eventos F1 existentes do EPG, sem reprises, com a mesma sessão
  e início até 45 minutos do horário publicado. Nenhum canal ou evento é criado. O botão de
  transmissões mantém acesso à lista completa mesmo quando não existe correspondência segura.
- Histórico de evolução dos pontos, pontos máximos restantes e candidatos matemáticos ao título
  ficam indisponíveis nesta integração. Não há regulamento validado para todas as condições,
  inclusive Sprint, provas reduzidas e desempates; não se afirma título ou eliminação.

## Validação

- `MotorsportChampionshipTest`: horários, datas ausentes, estados confirmados, próxima corrida,
  diferença de pontos e vínculo com transmissões sem duplicação.
- `MotorsportChampionshipDataTest`: parsing, ordem oficial, calendário, Sprint, dados parciais,
  isolamento de temporadas, cache e fallback offline.
- `MotorsportChampionshipUiTest`: navegação real até a página, dados indisponíveis, retorno pelo
  Android e preservação dos eventos existentes. Requer aparelho/emulador.
- O workflow e a assinatura existentes permanecem inalterados; esta implementação não publica versões.
