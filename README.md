# SweetSpot

Dnevnik šećera u krvi za vlastiti poslužitelj. Očitanja s Bluetooth aparata **Contour Next**
automatski stižu u web aplikaciju koja radi u Dockeru.

- **`server/`** – web aplikacija: grafikon, prosjeci, popis očitanja, ručni unos, izvoz u CSV, SQLite baza
- **`agent/`** – program za PC koji preko Bluetootha čita aparat i šalje očitanja aplikaciji
- **`install.sh`** – instalacijska skripta za Proxmox ili postojeći Docker host

Docker slika: `ghcr.io/moordrak/sweetspot` (amd64 i arm64).

## 1. Instalacija web aplikacije

### Proxmox

Na Proxmox hostu, kao root:

```sh
wget https://github.com/moordrak/SweetSpot/releases/latest/download/install.sh
bash install.sh
```

Skripta stvara neprivilegirani Debian LXC kontejner (1 jezgra, 512 MB RAM-a, 4 GB diska, DHCP na `vmbr0`),
instalira Docker, pokreće SweetSpot i na kraju ispisuje adresu sučelja i generirani token.

Postavke se mijenjaju varijablama okoline:

```sh
CTID=150 STORAGE=local-zfs IP=192.168.1.60/24 GW=192.168.1.1 PORT=8085 bash install.sh
```

### Postojeći Docker host

```sh
bash install.sh --local
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

U stalnom načinu agent čeka da se aparat pojavi (nakon mjerenja ili kad ga uključite), preuzme sva
očitanja i pošalje ih. Poslužitelj odbacuje duplikate. Ako poslužitelj nije dostupan, očitanja ostaju
u `neposlano.json` i šalju se kasnije.

### Automatsko pokretanje

- **Windows:** Task Scheduler → Create Task → okidač "At log on" →
  radnja `pythonw.exe` s argumentom `C:\putanja\agent\sweetspot_agent.py`.
- **Linux:** systemd korisnički servis s `ExecStart=python3 /putanja/agent/sweetspot_agent.py`.

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
objavljuje je na `ghcr.io` i stvara release s `install.sh` i `sweetspot-agent.zip`.

## Napomena

SweetSpot je osobni dnevnik, a ne medicinski uređaj. Osjenčani raspon na grafikonu (3,9–10,0 mmol/L)
može se promijeniti na vrhu skripte u `server/index.html`.
