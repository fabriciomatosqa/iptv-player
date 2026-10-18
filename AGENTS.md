# AURORA IPTV Android — instruções permanentes

## Projeto e estrutura

- Repositório: https://github.com/fabriciomatosqa/iptv-player.git; branch principal: `main`.
- Aplicativo IPTV Android nativo; projeto Gradle `AuroraIPTV`, módulo único `:app`.
- Kotlin, Jetpack Compose/Material 3, coroutines, Paging, Coil e Media3/ExoPlayer.
- Configuração confirmada: JDK 17, AGP 8.7.3, Kotlin 2.0.21, minSdk 24,
  compileSdk/targetSdk 35. O workflow usa Gradle 8.9.
- Configurações: `settings.gradle.kts`, `build.gradle.kts` e `app/build.gradle.kts`.
- Código: `app/src/main/java/br/com/fabriciomatos/iptvplayer/`.
- Entrada e estado: `MainActivity.kt`, `AuroraViewModel.kt`; player: `VideoPlayer.kt`.
- Playlists e catálogo: `Playlist.kt`, `PlaylistRepository.kt`, `PagedCatalog.kt`,
  `CatalogIdentity.kt` e `CatalogIdentityMigration.kt`.
- Preferências e progresso: `AppSettings.kt`, `PlaybackProgress.kt`; guia: `Guide.kt`.
- Esportes: arquivos `Football*`, `Sports*` e `Motorsport*` nesse mesmo diretório.
- Atualização online: `AppUpdateRepository.kt`, `AppUpdateController.kt`, `AppUpdateScreen.kt`.
- Recursos Android e traduções: `app/src/main/res/`; manifesto: `app/src/main/AndroidManifest.xml`.
- Testes: `app/src/test/`, `app/src/androidTest/`, `test/` e `release/test_prepare_android.py`.
- Automação: `.github/workflows/build-apk.yml`; publicação: scripts em `release/`.
- Documentação: `README.md`, `docs/`; traduções: `tools/generate-locales.py`.

## Início de cada tarefa e Git

- Confirme o diretório, leia estas instruções e execute `git status`,
  `git branch --show-current` e `git remote -v` antes de editar.
- Verifique possíveis mudanças remotas: consulte `git ls-remote origin refs/heads/main`
  e compare com `git rev-parse HEAD` e `git rev-parse origin/main`.
  Se necessário e permitido pela tarefa, use `git fetch origin` para inspecionar divergências;
  não integre mudanças automaticamente. Informe quando a consulta remota não for possível.
- Preserve alterações locais do usuário; não sobrescreva arquivos ou reverta trabalho alheio.
- Não execute pull, checkout, reset, clean ou operações destrutivas sem avaliar o estado
  e a autorização da tarefa. Não faça integração automática se houver divergências.
- Commits, pushes, releases e mudanças de versão exigem autorização explícita.
- Push em `main` e execução manual do workflow nessa branch podem publicar um release;
  só os execute com autorização que inclua esse efeito.

## Dados e compatibilidade

- Preserve playlists, lista ativa, catálogos SQLite, favoritos, séries favoritas,
  preferências, controle parental, guia, últimos conteúdos e progresso de reprodução.
- Não apague dados, não recrie bancos e não adote migrações destrutivas como solução.
- Mudanças de esquema ou identidade devem migrar dados existentes e manter vínculos
  de favoritos e progresso, inclusive entre playlists e após atualização do aplicativo.
- Preserve o catálogo em falhas de rede e a importação em fluxo/paginação para listas grandes.
- Valide persistência após reabrir o aplicativo e compatibilidade com dados anteriores
  quando a alteração afetar armazenamento, importação ou migração.
- Preserve `applicationId`/namespace `br.com.fabriciomatos.iptvplayer`, assinatura existente,
  compatibilidade Android e contratos do atualizador online.
- Não gere uma chave substituta. A chave original fica em `.signing/aurora-debug.keystore`,
  restaurada no CI por cache ou pelo secret `AURORA_KEYSTORE_BASE64`.
- Se a chave original estiver indisponível, informe o bloqueio; não contorne verificações.
- Só altere `versionCode`/`versionName` com autorização explícita; em publicação autorizada,
  preserve a progressão do código e a instalação sobre a versão anterior sem perda de dados.

## Segurança e escopo

- Nunca exponha nem versione chaves, senhas, tokens, keystores ou outros segredos.
- Não reproduza valores sensíveis existentes em código, logs, relatórios ou exemplos.
- Trate URLs de playlists e credenciais de provedores como dados privados; use fixtures sintéticas.
- Respeite `.gitignore`, inclusive `.signing/`, `local.properties` e artefatos de build.
- Priorize mudanças pequenas, seguras e testáveis; inspecione apenas arquivos pertinentes.
- Não instale dependências, faça refatorações amplas ou altere configurações sem necessidade.

## Validação, compilação e publicação

- Ajuste a validação ao impacto: testes unitários para lógica, testes de persistência para
  dados/migrações e instrumentação para interface, navegação e reprodução afetadas.
- Informe o que foi validado e qualquer limitação; não declare sucesso sem evidência.
- Alterações apenas documentais dispensam build, lint e emulador; revise o diff e o estado Git.
- O workflow `build-apk.yml` roda em pull requests, push em `main` e `workflow_dispatch`.
- Compila com `gradle --no-daemon assembleDebug`, usando a assinatura persistente original.
- `release/prepare_android.py` verifica a assinatura e prepara os metadados de atualização.
- O artefato `aurora-iptv-apk` contém `aurora-iptv.apk`, `aurora-update.json`
  e `aurora-iptv.apk.sha256` (origem: `app/build/outputs/apk/debug/`).
- A etapa de verificação executa `gradle --no-daemon testDebugUnitTest lintDebug
  compileDebugAndroidTestKotlin`, testes Python em `release/` e
  `bash test/run-android-checks.sh` em emulador API 29.
- Consulte o YAML para executar os comandos Gradle acima em uma única linha.
- A publicação depende de build e verificação aprovados e ocorre apenas em push ou
  execução manual na `main`, via `release/publish_android.py`.
- Releases Android usam o padrão `android-vX.Y.Z`; preserve o conjunto completo de
  APK, manifesto de atualização e SHA-256, bem como as verificações do atualizador.
- Não dispare CI/publicação nem altere workflows para contornar verificações sem autorização.
- Ao concluir qualquer tarefa, revise `git diff` e `git status`; resuma alterações,
  validação e pendências, sem fazer commit ou push por iniciativa própria.
