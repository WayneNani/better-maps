# Eigener Photon-Server für die Online-Suche

Die optionale Online-Suche (Einstellungen → Online-Suche, standardmäßig aus) nutzt die öffentliche Photon-Instanz
`photon.komoot.io`, die nur in vertretbarem Umfang genutzt werden darf. Für regelmäßige Nutzung besser einen eigenen
Server mit Podman betreiben und seine Adresse unter Einstellungen → Online-Suche → Server eintragen
(z. B. `http://192.168.1.10:2322`). Für Deutschland braucht es etwa 7,5 GB Download, 20 GB Platte und 4–8 GB RAM; der
Datenbank-Dump muss zur Photon-Version passen (hier 1.0).

```sh
mkdir -p ~/photon && cd ~/photon
# Photon-Jar (1.0.x) von https://github.com/komoot/photon/releases laden und als photon.jar speichern
# Wöchentlicher Dump von GraphHopper; andere Regionen: https://download1.graphhopper.com/public/
curl -L https://download1.graphhopper.com/public/europe/germany/photon-db-germany-1.0-latest.tar.bz2 \
  | bzip2 -cd | tar x          # legt ./photon_data an
podman run -d --name photon -p 2322:2322 -v ~/photon:/photon:Z -w /photon \
  docker.io/library/eclipse-temurin:21-jre java -Xmx6g -jar photon.jar serve -listen-ip 0.0.0.0
curl 'http://localhost:2322/api?q=Marienplatz&limit=1'
```

Zum Aktualisieren einen neuen Dump in ein anderes Verzeichnis entpacken, gegen `photon_data` tauschen und den
Container neu starten.
