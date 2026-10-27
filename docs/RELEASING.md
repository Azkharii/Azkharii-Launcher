# Publicación y actualización de Cinematic TV

## Flujo automatizado

- Cada cambio en main ejecuta los controles de calidad y el workflow .github/workflows/release.yml.
- El workflow crea una versión firmada, etiqueta v1.0.N y publica en GitHub Releases el APK cinematic-tv-launcher-release.apk junto con su checksum SHA-256.
- La versión de cada release es versionName=1.0.N y versionCode=N, donde N=1000 + GITHUB_RUN_NUMBER. Esto mantiene una secuencia creciente que supera los códigos de las versiones de prueba antiguas.
- El actualizador consulta el feed público de este repositorio: https://api.github.com/repos/Azkharii/Azkharii-Launcher/releases/latest. Comprueba versión, tamaño, identificador de paquete y SHA-256 antes de abrir el instalador de Android.
- En la TV, la comprobación se realiza al reanudar la aplicación y cada seis horas mientras permanece activa. Al encontrar una versión nueva, muestra un aviso; Android exige que la persona confirme la instalación. No se intenta instalar silenciosamente.

## Requisito de configuración única: clave de firma

El workflow no puede publicar una actualización instalable hasta configurar la clave de firma. El repositorio no tiene una release publicada y el workflow necesita secretos de firma en la configuración de GitHub. No se debe introducir una clave efímera por ejecución ni guardar un keystore o contraseñas en el repositorio público.

1. Crear una clave persistente fuera del repositorio y guardarla de forma segura. Por ejemplo:

   ```sh
   keytool -genkeypair -v -keystore cinematic-tv-release.jks -alias launcher-release -keyalg RSA -keysize 4096 -validity 10000
   ```

   Guardar una copia segura del archivo .jks, su contraseña, el alias y la contraseña de la clave. No perder ni regenerar esta clave después de distribuir el primer APK estable: Android solo acepta actualizaciones firmadas con el mismo certificado.

2. Codificar el keystore como una sola cadena Base64 sin subir el archivo:

   ```sh
   base64 cinematic-tv-release.jks | tr -d '\n'
   ```

3. En GitHub, abrir Settings → Secrets and variables → Actions del repositorio Azkharii/Azkharii-Launcher y crear estos cuatro repository secrets:

   - ANDROID_KEYSTORE_BASE64: la salida Base64 del paso 2.
   - ANDROID_KEYSTORE_PASSWORD: contraseña del keystore.
   - ANDROID_KEY_ALIAS: launcher-release (o el alias elegido al crear la clave).
   - ANDROID_KEY_PASSWORD: contraseña de la clave.

4. Integrar los cambios a main. GitHub Actions publicará la release firmada automáticamente cuando los controles pasen. Si falta algún secreto, el workflow falla expresamente y no publica un APK sin una firma estable.

Importante para la instalación inicial: el APK de depuración usa el identificador com.cinematic.tvlauncher.debug; no se actualiza en el sitio con la versión estable com.cinematic.tvlauncher. Para recibir actualizaciones por el launcher hay que instalar una vez el APK de una release firmada. Si ya hay una versión estable instalada con otra clave, debe recuperarse esa misma clave de firma; reemplazarla impediría actualizar esa instalación sin desinstalarla.

No incrustar un Personal Access Token ni otro secreto de GitHub en el APK. El feed de releases es público y el APK descarga los activos de ese feed.

## “Continuar viendo”: fuente de datos y diagnóstico

La sección muestra títulos reales que expone el proveedor Android TV. Una fila con título válido se conserva aunque el proveedor no publique un URI de reanudación y el paquete de origen no pueda abrirse; en ese caso la tarjeta sigue visible, pero al pulsarla se informa que no hay una ruta compatible. No se inventan títulos, pósteres, posiciones de reproducción ni enlaces. La visibilidad de la sección sigue dependiendo de que el proveedor entregue filas con títulos válidos; si el proveedor entrega cero filas o bloquea la consulta, el launcher no puede fabricar contenido.

Los pósteres, cuando existen, se cargan desde la URI publicada por el proveedor. El título está centrado dentro de la tarjeta; ese texto no controla la visibilidad del carrusel.
