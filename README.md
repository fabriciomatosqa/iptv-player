# AURORA IPTV

Aplicativo IPTV para Android 7+ com identidade visual inspirada nas auroras boreais.

## Versão 1.3.0

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

O APK fica em **Actions → Build AURORA IPTV APK → execução concluída → Artifacts → aurora-iptv-apk**. O pacote `br.com.fabriciomatos.iptvplayer` e a chave de teste em cache foram preservados para instalação sobre as versões 1.1.0 e 1.2.0.
