import json
from pathlib import Path
B=Path(__file__).resolve().parents[1]
p=B/'i18n/source-translations.tsv'
langs=['uk','ro','fr','es','de']; dicts={lang:{} for lang in langs}
for n,row in enumerate(p.read_text().splitlines(),1):
 if not row or row.startswith('#'):continue
 cols=row.split('|')
 assert len(cols)==6,(n,len(cols))
 for lang,translated in zip(langs,cols[1:]):
  assert translated.strip(),(n,lang)
  dicts[lang][cols[0]]=translated
# More UI phrases emitted by JS or used as placeholders. Keep technical data and OAuth identifiers untouched.
supplement={
'Leave blank to retain existing':['Залиште порожнім, щоб зберегти поточне','Lăsați gol pentru a păstra valoarea existentă','Laisser vide pour conserver la valeur actuelle','Dejar en blanco para conservar el valor','Leer lassen, um aktuellen Wert beizubehalten'],
'New password (12+ characters)':['Новий пароль (від 12 символів)','Parolă nouă (minim 12 caractere)','Nouveau mot de passe (12 caractères min.)','Contraseña nueva (mínimo 12 caracteres)','Neues Passwort (mindestens 12 Zeichen)'],
'Password (12+ characters)':['Пароль (від 12 символів)','Parolă (minim 12 caractere)','Mot de passe (12 caractères min.)','Contraseña (mínimo 12 caracteres)','Passwort (mindestens 12 Zeichen)'],
'Paste a new webhook URL to add or replace':['Вставте новий URL webhook для додавання або заміни','Lipiți un URL webhook nou pentru adăugare sau înlocuire','Collez une nouvelle URL webhook pour ajouter ou remplacer','Pegue una nueva URL webhook para añadir o reemplazar','Neue Webhook-URL zum Hinzufügen oder Ersetzen einfügen'],
'Optional correlation ID':['Необов’язковий ID кореляції','ID de corelație opțional','ID de corrélation facultatif','ID de correlación opcional','Optionale Korrelations-ID'],
'Host or socket':['Хост або сокет','Gazdă sau socket','Hôte ou socket','Host o socket','Host oder Socket'],
'A bind secret is configured.':['Секрет прив’язки налаштовано.','Parola de bind este configurată.','Le secret de liaison est configuré.','El secreto de enlace está configurado.','Ein Bind-Secret ist konfiguriert.'],
'No bind secret is configured.':['Секрет прив’язки не налаштовано.','Parola de bind nu este configurată.','Aucun secret de liaison configuré.','No se ha configurado un secreto de enlace.','Kein Bind-Secret konfiguriert.'],
'Client secret configured.':['Секрет клієнта налаштовано.','Secret client configurat.','Secret client configuré.','Secreto de cliente configurado.','Client-Secret konfiguriert.'],
'Client secret not configured.':['Секрет клієнта не налаштовано.','Secret client neconfigurat.','Secret client non configuré.','Secreto de cliente no configurado.','Client-Secret nicht konfiguriert.'],
'Stored in database · overrides Docker':['Збережено в базі даних · пріоритет над Docker','Salvat în baza de date · prioritar față de Docker','Enregistré en base · prioritaire sur Docker','Guardado en la base · prevalece sobre Docker','In Datenbank gespeichert · Vorrang vor Docker'],
'Using Docker defaults · not yet saved in UI':['Використовуються типові значення Docker · ще не збережено','Se folosesc valorile Docker · nesalvate în interfață','Valeurs par défaut Docker · non enregistrées dans l’interface','Valores Docker predeterminados · sin guardar en la interfaz','Docker-Standardwerte · noch nicht in der UI gespeichert'],
'Testing provider connection…':['Перевірка з’єднання з постачальником…','Testare conexiune furnizor…','Test de connexion au fournisseur…','Probando conexión del proveedor…','Anbieterverbindung wird getestet…'],
'Saving authentication settings…':['Збереження налаштувань автентифікації…','Se salvează setările de autentificare…','Enregistrement des paramètres d’authentification…','Guardando configuración de autenticación…','Authentifizierungseinstellungen werden gespeichert…'],
'Operation completed.':['Операцію завершено.','Operațiune finalizată.','Opération terminée.','Operación completada.','Vorgang abgeschlossen.'],
'Operation failed.':['Операція не вдалася.','Operațiunea a eșuat.','Échec de l’opération.','La operación falló.','Vorgang fehlgeschlagen.'],
'Unable to reach the server; no settings were changed.':['Немає зв’язку із сервером; налаштування не змінено.','Serverul nu este disponibil; setările nu au fost modificate.','Serveur inaccessible ; aucun paramètre modifié.','No se puede conectar al servidor; no se modificaron los ajustes.','Server nicht erreichbar; Einstellungen unverändert.'],
'Connected':['Підключено','Conectat','Connecté','Conectado','Verbunden'],
'Disconnected':['Відключено','Deconectat','Déconnecté','Desconectado','Getrennt'],
'Connecting to native clamdtop…':['Підключення до clamdtop…','Conectare la clamdtop…','Connexion à clamdtop…','Conectando a clamdtop…','Verbindung zu ClamdTop…'],
'Terminal renderer is missing':['Відсутній компонент термінала','Lipsește componenta terminalului','Le moteur du terminal est absent','Falta el renderizador del terminal','Terminaldarstellung fehlt'],
'Statistics from the external REST API':['Статистика зовнішнього REST API','Statistici din API-ul REST extern','Statistiques de l’API REST externe','Estadísticas de la API REST externa','Statistiken der externen REST-API'],
'External API rejected the request':['Зовнішній API відхилив запит','API-ul extern a respins cererea','L’API externe a rejeté la requête','La API externa rechazó la solicitud','Externe API hat die Anfrage abgelehnt'],
'Invalid REST response':['Неправильна відповідь REST','Răspuns REST invalid','Réponse REST invalide','Respuesta REST no válida','Ungültige REST-Antwort'],
'Unknown':['Невідомо','Necunoscut','Inconnu','Desconocido','Unbekannt'],
'unknown':['невідомо','necunoscut','inconnu','desconocido','unbekannt'],
'OK':['OK','OK','OK','OK','OK'],
}
for original,vals in supplement.items():
 for l,v in zip(langs,vals):dicts[l][original]=v
# Correct a term in the Ukrainian dictionary.
dicts['uk']['External API is not configured. Set CLAMAV_REST_URL in Docker Compose.']='Зовнішній API не налаштовано. Встановіть CLAMAV_REST_URL у Docker Compose.'
# Do not translate proper names, usernames, protocols or terminal output.
out=B/'src/main/resources/static/js/i18n-catalog.js';out.write_text('/* ClamAV Web UI v2.0.0: static UI translations; never send data to external translation services. */\nwindow.ClamavTranslationCatalog = '+json.dumps(dicts,ensure_ascii=False,sort_keys=True,separators=(',',':'))+';\n')
print('catalog',len(dicts['ro']),'keys for',','.join(langs),'size',out.stat().st_size)
