# SweetSpot

Dnevnik šećera u krvi za vlastiti poslužitelj. Očitanja s Bluetooth aparata **Contour Next**
automatski stižu u web aplikaciju koja radi u Dockeru.

- **`server/`** – web aplikacija: grafikon, prosjeci, popis očitanja, ručni unos, izvoz u CSV, SQLite baza
- **`agent/`** – program za PC (Windows instalacija s ikonom u system trayu, ili Python skripta) koji preko Bluetootha čita aparat i šalje očitanja aplikaciji
- **`sweetspot_install.sh`** – instalacijska skripta za Proxmox ili postojeći Docker host

Docker slika: `ghcr.io/moordrak/sweetspot` (amd64 i arm64).

## 1. Instalacija web aplikacije

### Proxmox

Na Proxmox hostu, kao root:

```sh
wget -O sweetspot_install.sh https://github.com/moordrak/SweetSpot/releases/latest/download/sweetspot_install.sh
bash sweetspot_install.sh
```

Skripta stvara neprivilegirani Debian LXC kontejner (1 jezgra, 512 MB RAM-a, 4 GB diska, DHCP na `vmbr0`),
instalira Docker, pokreće SweetSpot i na kraju ispisuje adresu sučelja i generirani token.

Na početku pita dvije stvari:

- **spremište** za disk kontejnera – nudi popis aktivnih spremišta sa slobodnim prostorom,
- **root lozinku** kontejnera – prazan unos znači kontejner bez lozinke (ulaz samo s hosta: `pct enter CTID`).

Za instalaciju bez pitanja zadajte `STORAGE=...` i `ROOT_PASSWORD=...`.

Postavke se mijenjaju varijablama okoline:

```sh
CTID=150 STORAGE=local-zfs IP=192.168.1.60/24 GW=192.168.1.1 PORT=8085 bash sweetspot_install.sh
```

### Postojeći Docker host

```sh
bash sweetspot_install.sh --local
```

Postavlja SweetSpot u `/opt/sweetspot`. Ponovno pokretanje nadograđuje sliku, a token i baza ostaju.

### Ručno, s Docker Composeom

```sh
git clone https://github.com/moordrak/SweetSpot.git && cd SweetSpot
cp .env.example .env       # upišite vlastiti API_TOKEN
docker compose up -d
```

Sučelje je na `http://IP-ADRESA:8085`. Preglednik traži prijavu: korisničko ime je proizvoljno,
lozinka je `API_TOKEN`. Baza je u mapi `./data` – nju uključite u backup.

Aplikacija sama ne radi HTTPS. Izvan kućne mreže izlažite je samo preko reverse proxyja s TLS-om
(dovoljan je običan `proxy_pass http://IP:8085;`).

## 2. Agent na PC-u

### Windows

Preuzmite `SweetSpot-Agent-Setup-x.y.z.exe` iz [zadnjeg izdanja](https://github.com/moordrak/SweetSpot/releases/latest)
i pokrenite ga. Instalacija ne traži administratorska prava i nudi pokretanje pri prijavi u Windows.

Agent radi kao ikona pored sata (system tray). Pri prvom pokretanju otvara **Postavke**, gdje upisujete
adresu poslužitelja i token; gumb **Provjeri vezu** odmah pokazuje jesu li ispravni.

Nakon spremanja postavki otvara se **čarobnjak za povezivanje aparata**, koji vodi kroz četiri koraka:

1. **Priprema** – što uključiti i kako staviti aparat u način uparivanja.
2. **Traženje aparata** – popis Contour aparata u blizini s jačinom signala.
3. **Spajanje** – uparivanje i probno preuzimanje očitanja; ako ne uspije, nudi savjete i ponovni pokušaj.
4. **Gotovo** – broj preuzetih očitanja i zadnje očitanje.

Povezani aparat se pamti, pa agent ubuduće traži samo njega. Čarobnjak se može ponovno pokrenuti
iz izbornika (**Poveži aparat…**).

Desni klik na ikonu:

- stanje agenta i vrijeme zadnje sinkronizacije
- **Otvori SweetSpot** – web sučelje u pregledniku (i dvoklik na ikonu)
- **Sinkroniziraj sada** – odmah traži aparat
- **Postavke…** – adresa, token, pauza nakon preuzimanja
- **Poveži aparat…** – čarobnjak za povezivanje aparata
- **Pokreni s Windowsima** – uključuje i isključuje automatsko pokretanje
- **Zapisnik** – otvara `agent.log`
- **Izlaz**

Ikona je plava kad sve radi, narančasta kad nešto ne valja (Bluetooth isključen, poslužitelj nedostupan)
i siva dok agent nije podešen. Postavke, zapisnik i neposlana očitanja su u `%APPDATA%\SweetSpot`.

Windows SmartScreen može upozoriti da je izdavač nepoznat jer instalacija nije digitalno potpisana:
**More info → Run anyway**.

### Linux i macOS (Python)

Potreban je Python 3.9+ i Bluetooth na računalu. Preuzmite `sweetspot-agent.zip` iz
[zadnjeg izdanja](https://github.com/moordrak/SweetSpot/releases/latest) i raspakirajte ga.

```sh
pip install bleak
```

U `agent/config.ini` upišite adresu aplikacije i token, zatim:

```sh
python sweetspot_agent.py --test    # provjera veze s poslužiteljem
python sweetspot_agent.py --once    # jedno preuzimanje s aparata
python sweetspot_agent.py           # radi stalno u pozadini
```

Za automatsko pokretanje na Linuxu: systemd korisnički servis s
`ExecStart=python3 /putanja/agent/sweetspot_agent.py`.

### Kako agent radi

Agent čeka da se aparat pojavi (nakon mjerenja ili kad ga uključite), preuzme sva očitanja i pošalje ih.
Poslužitelj odbacuje duplikate. Ako poslužitelj nije dostupan, očitanja se čuvaju i šalju kasnije.

### Bluetooth napomene

- Radi s modelima koji imaju Bluetooth (Contour Next One, Contour Next Gen).
- Aparat se spaja samo na jedan uređaj: dok agent preuzima, isključite Bluetooth na mobitelu
  ako koristite aplikaciju Contour Diabetes.
- Pri prvom spajanju aparat može tražiti uparivanje – stavite ga u način uparivanja
  prema uputama aparata i potvrdite na računalu.

## API

| Metoda | Putanja | Opis |
|---|---|---|
| GET | `/api/readings` | sva očitanja |
| POST | `/api/readings` | `{"readings":[{"time":"2026-11-14T08:30:00","mmol":5.6,"source":"aparat"}]}` |
| DELETE | `/api/readings/<id>` | brisanje |
| GET | `/api/export.csv` | izvoz |
| GET | `/api/health` | provjera rada (bez prijave) |

Autorizacija: zaglavlje `Authorization: Bearer <API_TOKEN>`.

## Izdavanje nove verzije

Promijenite broj u datoteci `VERSION` i pošaljite promjenu na granu `main`
(ili pošaljite oznaku oblika `v1.1.0`). GitHub Actions tada gradi Docker sliku,
objavljuje je na `ghcr.io`, gradi Windows instalaciju agenta i stvara release sa svim datotekama.

## Napomena

SweetSpot je osobni dnevnik, a ne medicinski uređaj. Osjenčani raspon na grafikonu (3,9–10,0 mmol/L)
može se promijeniti na vrhu skripte u `server/index.html`.
