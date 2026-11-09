<div align="center">
  <img src="docs/navia_btv.png" alt="Navia Browser TV Banner" width="100%">
  <br>
  <img src="docs/icon.png" alt="Navia Icon" width="120" style="border-radius: 20px;">
  
  # 📺 Navia Browser TV
  
  [![Android TV](https://img.shields.io/badge/Platform-Android%20TV%20%7C%20Google%20TV-blue.svg?style=for-the-badge&logo=android)](https://github.com/pekorinz/Navia-Browser-TV)
  [![Kotlin](https://img.shields.io/badge/Language-Kotlin-purple.svg?style=for-the-badge&logo=kotlin)](https://kotlinlang.org/)
  [![API](https://img.shields.io/badge/Min%20SDK-26%20%28Android%208.0%2B%29-green.svg?style=for-the-badge&logo=android)](https://developer.android.com/)
  [![License](https://img.shields.io/badge/License-MIT-orange.svg?style=for-the-badge)](LICENSE)

  <br>

  [![VirusTotal](https://img.shields.io/badge/VirusTotal-0%20%2F%2067%20Clean-success.svg?style=for-the-badge&logo=virustotal)](https://www.virustotal.com/gui/file/1da387fba083c574fe279d99030e4cce1f8c4f9bc37db34aac511688665ed246)
</div>

---

**Navia Browser TV** es un navegador web moderno, ultraligero y de alta velocidad diseñado específicamente para **Android TV**, **Google TV** y **TV Boxes**. Ofrece una experiencia de navegación pura y sin distracciones con control remoto (D-Pad), soporte avanzado para aplicaciones web de vídeo como **YouTube TV**, bloqueo de anuncios de múltiples capas, gestor de DNS cifrado (DoH) y sistema de actualizaciones automáticas.

### 🛡️ Garantía de Seguridad
Este proyecto es transparente y seguro. El APK de producción ha sido analizado exhaustivamente por **67 motores antivirus** en VirusTotal (incluyendo Kaspersky, ESET, BitDefender, Google y Microsoft), con un resultado de **0 detecciones**. Puedes verificar el reporte oficial haciendo clic en la insignia superior o [aquí](https://www.virustotal.com/gui/file/1da387fba083c574fe279d99030e4cce1f8c4f9bc37db34aac511688665ed246).

---

## 🚀 La Filosofía de Navia: Simplicidad y Libertad Absoluta

A diferencia de muchos navegadores populares en la Play Store para Android TV —que suelen estar recargados de carruseles pesados, recomendaciones forzadas de películas, portadas intrusivas e interfaces lentas y complejas—, **Navia Browser TV apuesta por la simplicidad real**.

Creemos firmemente que **un navegador debe ser una herramienta para hacer lo que a ti te dé la gana**, no una plataforma publicitaria ni un agregador de contenido. Mientras que otros navegadores de TV pueden resultar incómodos, invasivos o sobrecargados de funciones que nadie pidió, Navia te entrega una interfaz limpia, ágil y rápida, enfocada únicamente en darte el control total de la web desde tu mando a distancia.

---

## 🌟 Características Principales

### 🎮 Optimización para TV y D-Pad (D-Pad Engine)
Diseñado desde cero para mandos a distancia de televisión sin necesidad de mouse físico ni pantalla táctil:
* **Modo FOCO (Focus Traversal)**: Navegación inteligente que salta entre enlaces, botones e insumos interactivos usando las flechas del mando.
* **Modo CURSOR (Puntero Virtual)**: Muestra un puntero suave en pantalla controlado por el D-Pad, con velocidad personalizable (*Lenta, Media, Rápida*).
* **Modo SCROLL**: Desplazamiento directo vertical y horizontal por las páginas web.
* **Atajos de Control**: Mantén presionado el botón `ATRÁS` para desplegar el menú de herramientas rápido o el panel exclusivo de YouTube TV.

### 🛡️ Bloqueo de Anuncios y Privacidad (AdBlocker)
Navegación limpia y sin interrupciones ni rastreadores:
* **Filtros Integrados**: Más de 100,000 dominios y 9,500 reglas de rutas locales.
* **Suscripciones Adblock Plus / EasyList**: Descarga y actualización automática de listas comunitarias globales con intervalo configurable (diario, semanal, etc.).
* **Lista Blanca por Sitio**: Pausa o activa el bloqueo para dominios específicos desde la barra superior.
* **Estadísticas en Tiempo Real**: Contador de peticiones e imprevistos bloqueados por página, por día e histórico.

### 🔒 Gestor DNS Cifrado sobre HTTPS (DoH Local)
Proxy DNS local integrado que resuelve consultas cifradas directamente en el dispositivo:
* **Proveedores Incluidos**:
  * 🌐 Cloudflare (`1.1.1.1`)
  * 🔍 Google (`8.8.8.8`)
  * 🛡️ Quad9 (`9.9.9.9`)
  * 🚫 AdGuard (`dns.adguard.com`)
* **DoH Personalizado**: Capacidad de enrutar y probar servidores DNS seguros introduciendo URLs personalizadas (NextDNS, Pi-hole DoH, etc.).
* **Memoria Caché Interna**: Acelera la resolución de dominios frecuentes.

### 📺 Soporte Especial y Add-ons para YouTube TV
Interfaz adaptada con User-Agent dedicado de TV e inyección de scripts para una experiencia de reproducción perfecta:
* **AdBlocker para YouTube TV**: Elimina la carga útil de anuncios de vídeo antes de la reproducción.
* **Corrector UI/UX**: Quita las cajas negras molestas en los controles del reproductor y añade un velo suave para el texto.
* **Prevención de Pausas**: Mantiene la sesión activa para evitar interrupciones por inactividad.
* **Barra Inferior Exclusiva**: Menú rápido desplegable para alternar ajustes sin interrumpir la reproducción de vídeo.

### 🔄 Sistema de Actualizaciones Automatizado
* Comprobación automática e independiente de versiones desde el servidor remoto.
* **Pantalla Nativa de Notas**: Visualización detallada de novedades y changelog de la versión.
* **Descarga e Instalación Directa**: Control de descargas e instalador APK interno sin depender de tiendas externas.

### 📑 Pestañas, Marcadores e Incógnito
* **Gestor de Pestañas**: Drawer lateral con vista previa de pestañas abiertas y creación instantánea de nuevas ventanas.
* **Modo Incógnito**: Navegación privada que limpia historial, caché y cookies al activarse o cerrar.

---

## 🎨 Temas Adaptativos (Modo Claro / Modo Noche)

El navegador soporta cambio de tema en tiempo real:
* **Modo Noche (Oscuro)**: Diseñado en tonos azul pizarra oscuro para evitar la fatiga visual en salas oscuras.
* **Modo Claro**: Interfaz limpia con alto contraste para entornos iluminados.
* **Foco D-Pad**: Los botones e ítems seleccionados muestran un velo suave transparente en reposo y resaltado claro sin bordes al enfocarse con el control remoto.

---

## 🛠️ Requisitos e Instalación

### Requisitos del Sistema
* **Android OS**: Android 8.0 (API 26) o superior.
* **Dispositivo**: Android TV, Google TV, Fire TV Stick, Xiaomi TV Box, Shield TV o emulador Leanback.

### Instalación del APK de Producción
1. Descarga el archivo desde la sección de **Releases** de este repositorio.
2. Copia el APK a una memoria USB o transfiérelo a tu televisor (usando *Send Files to TV* o similar).
3. Habilita la opción de *Instalar aplicaciones de fuentes desconocidas* en los ajustes de seguridad de tu Android TV.
4. Abre tu explorador de archivos en la TV e instala el archivo.

---

## 📄 Licencia y Autores

Desarrollado y mantenido por **[Pekorinz / Navia Team](https://github.com/pekorinz)**.  
Inspirado originalmente en la filosofía de sencillez de navegadores móviles minimalistas, rediseñado completamente para Android TV.

Distribuido bajo la licencia **MIT**. Consulta el archivo `LICENSE` para más información.