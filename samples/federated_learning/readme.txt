
Paradigma 1: Addestramento Centralizzato Tradizionale (No Privacy)

• Attori (Pools): Edge Nodes (Tutti), Server Centrale.
• Flusso:
	1. Start Event: Generazione di nuovi dati sui nodi periferici.
	2. Service Task (Nodi): Trasferimento immediato e non protetto dei dati grezzi (raw data) verso il Cloud/Server.
	3. Data Store: Database centralizzato sul Server.
	4. Script Task (Server): Avvio della pipeline di addestramento su modello unico e monolitico.
	5. End Event: Modello centralizzato aggiornato.

Paradigma 2: Federated Learning (FL) Puro + Integrazione Open Data (INGV)

• Attori: Smartphone Isolati, Repository INGV, Server Centrale (Aggregatore).
• Flusso:
	1. Parallel Gateway (Fork): Il processo si sdoppia.
	2. Ramo FL: Lo Smartphone esegue un User Task/Script Task di addestramento locale sul proprio hardware. Invia tramite Message Flow solo i pesi (gradienti) al Server centrale per l'aggregazione (es. algoritmo FedAvg).
	3. Ramo INGV: Un Timer Start Event sul Server attiva un download periodico dal repository pubblico dell'INGV. I dati vengono trasformati localmente.
	4. Parallel Gateway (Join): Il Server centrale media i gradienti ricevuti dagli smartphone con l'addestramento effettuato sui dati INGV.
	5. End Event: Modello globale aggiornato distribuito nuovamente ai nodi.

Paradigma 3: Nodi Edge Autonomi (Isolati)

• Attori: Smartphone Singoli.
• Flusso:
	1. Start Event: Raccolta dati sensoriali.
	2. Script Task: Addestramento locale (On-Device AI). Il dato non lascia mai il dispositivo e il modello non viene condiviso con l'esterno.
	3. End Event: Modello locale ottimizzato per il singolo utente (Massima privacy, zero collaborazione).

Paradigma 4: Architettura Gerarchica / Fog Computing (Scuola e Workstation)

• Attori: Smartphone Studenti, Server Scuola (Aggregatore di 1° Livello), Workstation Centrale (Aggregatore di 2° Livello).
• Flusso:
	1. Start Event: Produzione dati all'interno del perimetro della scuola.
	2. Sub-Process (Trust Zone): Sfruttando la rete locale fidata, gli smartphone inviano i dati raw al Server della Scuola.
	3. Service Task (Server Scuola): Pulisce, anonimizza, compatta o esegue un pre-addestramento (generando un modello intermedio della scuola).
	4. Message Flow: Il Server Scuola invia il dataset aggregato (o il modello intermedio) alla Workstation Centrale.
	5. Script Task (Workstation): Esegue la fusione della conoscenza (Knowledge Fusion) tra i vari macro-nodi (Scuola 1, Scuola 2, ecc.).