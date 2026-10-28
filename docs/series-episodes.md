# Séries: temporadas, cenas e progresso

Ao selecionar uma série, os detalhes continuam mostrando sinopse e elenco. O botão
**Episódios** substitui o elenco por temporadas empilhadas verticalmente; cada temporada
possui uma linha de episódios com rolagem horizontal. O botão **Elenco** e Voltar
permitem retornar à apresentação da série. O comportamento funciona em retrato e paisagem.

Os carrosséis consultam o catálogo SQLite em páginas de 60 registros, com janela de
até 240 por temporada. Não carregam a playlist inteira nem alteram o filtro de origem.
O estado dos carrosséis e a temporada visível sobrevivem à tela cheia e à rotação.
Fechar os detalhes após reproduzir restaura categoria, busca e posição do catálogo.
O Voltar do sistema segue uma etapa por toque: tela cheia → episódios → elenco →
catálogo de origem. Um único tratamento coordena as etapas, inclusive se o toque
chegar antes de a interface concluir a transição para tela cheia. Na reprodução em
tela cheia, teclas Voltar do Android antigo e de controles remotos são encaminhadas
a esse tratamento antes do player nativo. Diálogos mantêm o tratamento normal.

## Cenas e progresso

- A cena pertence ao episódio: `still_path`, `movie_image`, captura ou miniatura do
  provedor. Uma capa geral anunciada como imagem do episódio é descartada.
- Na ausência da cena, a pesquisa complementar TVmaze exige identificação exata e
  única da série e do ano quando informado, seguida de temporada e número do episódio.
  IDs TVmaze não são confundidos com IDs dos streams. A fonte fica acessível na interface.
- Se não existe uma cena verificável, o cartão conserva um espaço reservado. O app
  não inventa uma cena, não usa a capa da série como cena e não abre todos os vídeos
  antecipadamente para gerar imagens.
- A flag é amarela nos episódios ainda não concluídos e verde nos concluídos.
  A barra usa posição e duração salvas. Sem duração real, a duração de metadados pode
  estimar a barra, limitada a 99%; essa estimativa não declara conclusão.
- Ao abrir um vídeo incompleto já executado, **Continuar**, **Do início** e **Cancelar**
  permitem escolher antes de iniciar o stream. Conteúdos novos, concluídos e canais
  ao vivo seguem diretamente. A repetição interna em caso de falha mantém sua posição.
- O histórico existente continua separado por playlist e URL, inclusive nos aliases
  de endereço já reparados pelo catálogo. Não há migração destrutiva ou novo esquema.

## Fotos e limites de armazenamento

Uma foto fornecida pelo provedor tem prioridade. Fotos já conhecidas de outras obras
podem ser reutilizadas por nome quando não há conflito. Registros anteriores de sinopses
também podem alimentar esse índice sem alterar o banco. Para os atores visíveis sem
foto, a consulta pública exige nome completo exato e resultado único; homônimos e
abreviações ambíguas conservam o avatar.

O índice descartável guarda até 1.000 nomes em `cacheDir`; URLs positivas expiram em
30 dias, negativas em seis horas e falhas em 15 minutos. As imagens binárias de todo o
app compartilham um único cache Coil com limite de **64 MiB**, incluindo fotos, cenas
e capas. Imagens antigas são removidas quando necessário; não há uma coleção crescente
de fotos permanentes. A memória de imagens limita-se a 10% da memória disponível ao app.
Os metadados complementares de cenas ficam limitados a 100 séries e 2.000 episódios
por série, com validade de sete dias e reaproveitamento de dados anteriores em falhas.

Só nomes e títulos públicos são usados nas pesquisas complementares. A playlist,
o endereço de reprodução e as credenciais do provedor não são enviados à TVmaze.
Referências: [API TVmaze](https://www.tvmaze.com/api) e
[cache compartilhado Coil 2.7](https://github.com/coil-kt/coil/blob/2.7.0/docs/image_loaders.md).

## Verificação

As verificações locais executam:

```sh
gradle --no-daemon testDebugUnitTest lintDebug compileDebugAndroidTestKotlin
python3 -m unittest discover -s release -p 'test_*.py'
```

Os testes Android incluem cenas distintas em servidor local, flags e proporções da
barra, duas direções de rolagem, reprodução real, retorno, rotação, favoritos,
persistência de mais de 400 progressos e reaproveitamento de fotos. O conjunto completo
de instrumentação e as verificações de force-stop continuam em `test/run-android-checks.sh`.
A regressão de navegação também envia Voltar na mesma passagem da thread de UI em
que a tela cheia é solicitada, em retrato e paisagem. Antes do retorno normal, os
testes aguardam a superfície de tela cheia realmente renderizada. As descrições de
cenas e conclusão usam o idioma salvo no app, independentemente do idioma do emulador.
Compilar os testes não significa executá-los; a execução exige APK com assinatura válida.
O fluxo de publicação existente continua condicionado a todas as verificações obrigatórias.

Validação local em 27/10/2026: **108 testes unitários e dois testes Python aprovados**;
lint sem erros; código de produção e todos os testes Android compilados. A execução
dos testes no emulador e a geração do APK com a chave original são feitas pelo fluxo
autorizado no GitHub, pois essa chave não está na cópia local. A versão preparada é
**1.12.26 / código 47**, com publicação condicionada à aprovação de todas as verificações.

Correção local em 28/10/2026: retorno do sistema centralizado, sincronização dos
testes com a tela cheia renderizada e descrições verificadas no idioma do app.
Os 108 testes unitários e os dois testes Python passaram; lint sem erros e todos
os testes Android compilados. A execução da regressão no emulador permanece
pendente do CI com a assinatura original.
