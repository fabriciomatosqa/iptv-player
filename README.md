# AURORA IPTV

Aplicativo IPTV para Android 7+ com identidade visual inspirada nas auroras boreais.

## Versão 1.1.0

- Abertura vertical com cartões Ao vivo, Filmes e Séries; abertura horizontal com painel de conteúdo e atalhos.
- Navegação em paisagem com menu, categorias, vídeo e guia Agora/A seguir. Tela cheia disponível no player.
- **Minha playlist** permite cadastrar e editar o nome e o endereço M3U. O endereço é salvo antes do carregamento, inclusive quando o servidor está indisponível.
- Cache local com substituição atômica, leitura linha a linha e atualização automática a cada 15 minutos enquanto o app está em primeiro plano.
- Atualização condicional HTTP (ETag/Last-Modified) quando o servidor oferece esse recurso; reutilização dos itens que não mudaram, sem reiniciar o vídeo.
- Busca sem distinção de acentos e filtros fora da thread da interface; listas desenhadas sob demanda.
- Favoritos e último conteúdo salvos no aparelho.
- Reprodução HLS, DASH e arquivos diretos compatíveis com o dispositivo; tratamento de falhas, nova tentativa e fallback de decodificador.
- EPG XMLTV/XMLTV.gz quando a M3U informa `url-tvg` ou `x-tvg-url`. Sem EPG, os canais continuam acessíveis e a interface informa a ausência de programação.

## Como usar

1. Abra **Minha playlist** e cole a URL M3U fornecida pelo seu provedor.
2. Toque em **Salvar playlist**. Não será preciso cadastrar novamente ao reabrir.
3. Escolha Ao vivo, Filmes, Séries ou Favoritos e toque no conteúdo para assistir.
4. Use o botão de tela cheia no vídeo ou volte ao início para navegar. A rotação mantém o player.

O aplicativo não inclui credenciais, canais ou uma assinatura de conteúdo. A disponibilidade de cada transmissão depende do servidor e do formato de mídia.

## Compilação e verificações

JDK 17, Gradle 8.9 e SDK Android 35:

```sh
gradle testDebugUnitTest lintDebug assembleDebug
```

O GitHub Actions executa os testes e gera o artifact `aurora-iptv-apk`. O pacote `br.com.fabriciomatos.iptvplayer` e a chave de teste em cache foram preservados. Instalação sobre versões recentes requer a mesma assinatura; a chave local usada apenas para validação não deve ser distribuída.
