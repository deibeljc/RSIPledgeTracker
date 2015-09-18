package sheets;

import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeRequestUrl;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeTokenRequest;
import com.google.api.client.googleapis.auth.oauth2.GoogleCredential;
import com.google.api.client.googleapis.auth.oauth2.GoogleTokenResponse;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.gdata.client.spreadsheet.*;
import com.google.gdata.data.spreadsheet.*;
import com.google.gdata.util.*;
import enums.InsertionType;
import main.RSIPledgeWatcher;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.*;
import java.text.DateFormat;
import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

public class SpreadsheetServices {

    private static final String CLIENT_ID = "1042477002358-1rpkvotdhseuvjlt042rkss00ngifvs5.apps.googleusercontent.com";
    private static final String REDIRECT_URI = "urn:ietf:wg:oauth:2.0:oob";
    private static final Collection<String> SCOPES = Arrays.asList("https://spreadsheets.google.com/feeds", "https://docs.google.com/feeds");
    private static final String CLIENT_SECRET = "9JQcX0iUE1J-MYI17_xICrjO";
    private static boolean authenticated;
    private static SpreadsheetService service;

    /**
     * Retrieve OAuth 2.0 credentials.
     *
     * @return OAuth 2.0 Credential instance.
     */
    static Credential getCredentials() throws IOException {
        HttpTransport transport = new NetHttpTransport();
        JacksonFactory jsonFactory = new JacksonFactory();

        // Step 1: Authorize -->
        String authorizationUrl =
                new GoogleAuthorizationCodeRequestUrl(CLIENT_ID, REDIRECT_URI, SCOPES).build();

        // Point or redirect your user to the authorizationUrl.
        System.out.println("Go to the following link in your browser:");
        System.out.println(authorizationUrl);

        // Read the authorization code from the standard input stream.
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
        System.out.println("What is the authorization code?");
        String code = in.readLine();
        // End of Step 1 <--

        // Step 2: Exchange -->
        GoogleTokenResponse response =
                new GoogleAuthorizationCodeTokenRequest(transport, jsonFactory, CLIENT_ID, CLIENT_SECRET,
                        code, REDIRECT_URI).execute();
        // End of Step 2 <--

        authenticated = true;
        // Build a new GoogleCredential instance and return it.
        return new GoogleCredential.Builder().setClientSecrets(CLIENT_ID, CLIENT_SECRET)
                .setJsonFactory(jsonFactory).setTransport(transport).build()
                .setAccessToken(response.getAccessToken()).setRefreshToken(response.getRefreshToken());
    }


    /**
     * This will get OAuth connections on the first attempt, and if it is successful then it will mark itself as
     * authenticated for the session. Then it will find the correct Spreadsheet then the correct sheet in that
     * spreadsheet and push the corresponding data properly.
     * @param data the current funds
     * @param date the date which will be modified to be the correct timezone later in the flow
     * @param citizens the current number of citizens
     * @param fleet the current fleet number (I think this is people with ships)
     * @param type the type of insertion you want to do, I.E HOURLY, DAILY, EVERYMINUTE
     * @param attempt the number of attempts, send in 1.
     * @throws InterruptedException
     */
    public static void updateSheetsWithData(Double data, Date date, Long citizens, Long fleet, InsertionType type, int attempt) throws InterruptedException {
        try {
            // TODO: Authorize the service object for a specific user (see other sections)
            if (!authenticated) {
                service = new SpreadsheetService("MySpreadsheetIntegration-v1");
                service.setOAuth2Credentials(getCredentials());
            }
            // Define the URL to request.  This should never change.
            URL SPREADSHEET_FEED_URL = new URL(
                    "https://spreadsheets.google.com/feeds/spreadsheets/private/full");

            // Make a request to the API and get all spreadsheets.
            SpreadsheetFeed feed = service.getFeed(SPREADSHEET_FEED_URL,
                    SpreadsheetFeed.class);
            List<SpreadsheetEntry> spreadsheets = feed.getEntries();

            if (spreadsheets.size() == 0) {
                // TODO: There were no spreadsheets, act accordingly.
            }

            // Get the appropriate spreadsheet to edit.
            SpreadsheetEntry spreadsheet = null;
            for (SpreadsheetEntry sheet : spreadsheets) {
                if (sheet.getTitle().getPlainText().equals(RSIPledgeWatcher.sheetName)) {
                    spreadsheet = sheet;
                }
            }

            if (spreadsheet == null) {
                RSIPledgeWatcher.logger.log(Level.SEVERE, "No spreadsheet named " + RSIPledgeWatcher.sheetName + " was found. Program Terminated.");
                throw new NoSuchElementException();
            }

            RSIPledgeWatcher.logger.info(spreadsheet.getTitle().getPlainText());

            WorksheetFeed worksheetFeed = service.getFeed(
                    spreadsheet.getWorksheetFeedUrl(), WorksheetFeed.class);

            WorksheetEntry worksheet = getWorkSheet(worksheetFeed, RSIPledgeWatcher.workSheetName);

            // Throw an error if nothing was found
            if (worksheet == null) {
                RSIPledgeWatcher.logger.info("That is not a valid sheet name!");
                throw new NoSuchElementException();
            }

            // Fetch the cell feed of the worksheet.
            URL cellFeedUrl = worksheet.getCellFeedUrl();
            CellFeed cellFeed = service.getFeed(cellFeedUrl, CellFeed.class);

            // Handle the data insertion in a more organized manner
            switch (type) {
                case DAILY:
                    updateDaily(cellFeed, data, citizens, fleet, date);
                    break;
                case HOURLY:
                    updateFundsHourly(cellFeed, data, date);
                    updateCitizensHourly(worksheetFeed, "Hourly Citizen Capture", citizens, date);
                    updateFleetHourly(worksheetFeed, "Hourly UEEFleet Capture", fleet, date);
                    break;
                case EVERYMINUTE:
                    updateRawData(cellFeed, data, citizens, fleet, date);
                    break;
                default:
                    RSIPledgeWatcher.logger.log(Level.SEVERE, "There is an invalid type selected. Stopped process");
                    throw new NoSuchElementException();
            }
        // Blanket catch all exception so we can easily retry.
        } catch (Exception e) {
            RSIPledgeWatcher.logger.info(e.toString());
            // Retry a lot...
            if (attempt <= 60) {
                Thread.sleep(1000 + new Random().nextInt(1000));
                RSIPledgeWatcher.logger.info("Trying again.. this is attempt " + attempt);
                updateSheetsWithData(data, date, citizens, fleet, type, attempt + 1);
            } else {
                RSIPledgeWatcher.logger.log(Level.SEVERE, "Tried " + attempt + " times.. they all failed. Ignoring this update for now.");
            }
        }
    }

    /**
     * Gets the worksheet based on the title.
     * @param worksheetFeed the open feed via the drive API.
     * @param name the name of the worksheet to find.
     * @return
     */
    private static WorksheetEntry getWorkSheet(WorksheetFeed worksheetFeed, String name) {
        List<WorksheetEntry> worksheets = worksheetFeed.getEntries();

        WorksheetEntry worksheet = null;
        for (WorksheetEntry workSheet : worksheets) {
            if (workSheet.getTitle().getPlainText().equals(name)) {
                worksheet = workSheet;
            }
        }

        return worksheet;
    }

    /**
     * This will properly insert the daily data in the correct format.
     * @param cellFeed the feed to the worksheet so cells can be modified
     * @param funds the current funding amount
     * @param citizens the current citizen amount
     * @param fleet the current fleet amount
     * @param date the date
     * @throws IOException
     * @throws ServiceException thrown when drive API has an issue.
     */
    private static void updateDaily(CellFeed cellFeed, Double funds, Long citizens, Long fleet, Date date) throws IOException, ServiceException {
        int insertLocation = 1;

        // Format the date.
        DateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd");
        // Convert it to the right time.
        TimeZone zone = new SimpleTimeZone((int) TimeUnit.HOURS.toMillis(1), "GMT+1");
        dateFormat.setTimeZone(zone);
        // For log.
        DateFormat dateTime = new SimpleDateFormat();
        dateTime.setTimeZone(zone);

        // Find where to insert the funds.
        for (CellEntry cell : cellFeed.getEntries()) {
            if (cell.getTitle().getPlainText().contains("A") && cell.getCell() != null) {
                if (cell.getPlainTextContent().equals(dateFormat.format(date))) {
                    insertLocation = cell.getCell().getRow();
                }
            }
        }

        CellEntry dataEntry = new CellEntry(insertLocation, 2, funds.toString());
        cellFeed.insert(dataEntry);
        CellEntry citizenEntry = new CellEntry(insertLocation, 4, citizens.toString());
        cellFeed.insert(citizenEntry);
        CellEntry fleetEntry = new CellEntry(insertLocation, 5, fleet.toString());
        cellFeed.insert(fleetEntry);

        RSIPledgeWatcher.logger.info("Updated sheet with " + NumberFormat.getNumberInstance(Locale.US).format(funds) + " at " + dateTime.format(date));
    }

    /**
     * This will properly insert the hourly funds data in the correct format
     * @param cellFeed the feed to the worksheet so cells can be modified
     * @param funds the current funding amount
     * @param date the date
     * @throws IOException
     * @throws ServiceException thrown when drive API has an issue.
     */
    private static void updateFundsHourly(CellFeed cellFeed, Double funds, Date date) throws IOException, ServiceException {
        int insertLocation = 1;

        // Format the date.
        DateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd");
        // Convert it to the right time.
        TimeZone zone = new SimpleTimeZone((int) TimeUnit.HOURS.toMillis(1), "GMT+1");
        dateFormat.setTimeZone(zone);
        // For log.
        DateFormat dateTime = new SimpleDateFormat("HH");
        dateTime.setTimeZone(zone);

        // Find where to insert the funds.
        for (CellEntry cell : cellFeed.getEntries()) {
            if (cell.getTitle().getPlainText().contains("A") && cell.getCell() != null) {
                if (cell.getPlainTextContent().equals(dateFormat.format(date))) {
                    insertLocation = cell.getCell().getRow();
                }
            }
        }

        RSIPledgeWatcher.logger.info("Funds " + Integer.parseInt(dateTime.format(date)) + 2);

        if (Integer.parseInt(dateTime.format(date)) == 23) {
            CellEntry dataEntry = new CellEntry(insertLocation + 1, 2, funds.toString());
            cellFeed.insert(dataEntry);
            CellEntry dataEntry2 = new CellEntry(insertLocation, 26, funds.toString());
            cellFeed.insert(dataEntry2);
        } else {
            CellEntry dataEntry = new CellEntry(insertLocation, Integer.parseInt(dateTime.format(date)) + 3, funds.toString());
            cellFeed.insert(dataEntry);
        }
    }

    /**
     * This establishes a new feed to a different worksheet and then updates the hourly citizen data accordingly
     * @param worksheetFeed The feed to a worksheet so a new one can be established.
     * @param name the name of the sheet to establish a connection with
     * @param citizens the current number of citizens
     * @param date the date
     * @throws IOException
     * @throws ServiceException
     */
    private static void updateCitizensHourly(WorksheetFeed worksheetFeed, String name, Long citizens, Date date) throws IOException, ServiceException {
        int insertLocation = 1;

        CellFeed cellFeed = service.getFeed(getWorkSheet(worksheetFeed, name).getCellFeedUrl(), CellFeed.class);

        // Format the date.
        DateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd");
        // Convert it to the right time.
        TimeZone zone = new SimpleTimeZone((int) TimeUnit.HOURS.toMillis(1), "GMT+1");
        dateFormat.setTimeZone(zone);
        // For log.
        DateFormat dateTime = new SimpleDateFormat("HH");
        dateTime.setTimeZone(zone);

        // Find where to insert the funds.
        for (CellEntry cell : cellFeed.getEntries()) {
            if (cell.getTitle().getPlainText().contains("A") && cell.getCell() != null) {
                if (cell.getPlainTextContent().equals(dateFormat.format(date))) {
                    insertLocation = cell.getCell().getRow();
                }
            }
        }

        RSIPledgeWatcher.logger.info("Citizen " + Integer.parseInt(dateTime.format(date)) + 2);

        if (Integer.parseInt(dateTime.format(date)) == 23) {
            CellEntry dataEntry = new CellEntry(insertLocation + 1, 2, citizens.toString());
            cellFeed.insert(dataEntry);
            CellEntry dataEntry2 = new CellEntry(insertLocation, 26, citizens.toString());
            cellFeed.insert(dataEntry2);
        } else {
            CellEntry dataEntry = new CellEntry(insertLocation, Integer.parseInt(dateTime.format(date)) + 3, citizens.toString());
            cellFeed.insert(dataEntry);
        }
    }

    /**
     * This establishes a new feed to a different worksheet and then updates the hourly fleet data accordingly
     * @param worksheetFeed The feed to a worksheet so a new one can be established.
     * @param name the name of the sheet to establish a connection with
     * @param fleet the current number of citizens with ships (?).
     * @param date the date
     * @throws IOException
     * @throws ServiceException
     */
    private static void updateFleetHourly(WorksheetFeed worksheetFeed, String name, Long fleet, Date date) throws IOException, ServiceException {
        int insertLocation = 1;

        CellFeed cellFeed = service.getFeed(getWorkSheet(worksheetFeed, name).getCellFeedUrl(), CellFeed.class);

        // Format the date.
        DateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd");
        // Convert it to the right time.
        TimeZone zone = new SimpleTimeZone((int) TimeUnit.HOURS.toMillis(1), "GMT+1");
        dateFormat.setTimeZone(zone);
        // For log.
        DateFormat dateTime = new SimpleDateFormat("HH");
        dateTime.setTimeZone(zone);

        // Find where to insert the funds.
        for (CellEntry cell : cellFeed.getEntries()) {
            if (cell.getTitle().getPlainText().contains("A") && cell.getCell() != null) {
                if (cell.getPlainTextContent().equals(dateFormat.format(date))) {
                    insertLocation = cell.getCell().getRow();
                }
            }
        }

        RSIPledgeWatcher.logger.info("Fleet " + Integer.parseInt(dateTime.format(date)) + 2);

        if (Integer.parseInt(dateTime.format(date)) == 23) {
            CellEntry dataEntry = new CellEntry(insertLocation + 1, 2, fleet.toString());
            cellFeed.insert(dataEntry);
            CellEntry dataEntry2 = new CellEntry(insertLocation, 26, fleet.toString());
            cellFeed.insert(dataEntry2);
        } else {
            CellEntry dataEntry = new CellEntry(insertLocation, Integer.parseInt(dateTime.format(date)) + 3, fleet.toString());
            cellFeed.insert(dataEntry);
        }
    }

    /**
     * This will dump the raw data into a new sheet
     * @param cellFeed the feed to the correct sheet for cell modification.
     * @param funds the current fund amount
     * @param citizens the current citizen amount
     * @param fleet the current fleet amount
     * @param date the date
     * @throws IOException
     * @throws ServiceException
     */
    private static void updateRawData(CellFeed cellFeed, Double funds, Long citizens, Long fleet, Date date) throws IOException, ServiceException {
        int insertLocation = 1;

        // Format the date.
        DateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        // Convert it to the right time.
        TimeZone zone = new SimpleTimeZone((int) TimeUnit.HOURS.toMillis(1), "GMT+1");
        dateFormat.setTimeZone(zone);
        // For log.
        DateFormat dateTime = new SimpleDateFormat();
        dateTime.setTimeZone(zone);

        // Find where to insert the funds.
        for (CellEntry cell : cellFeed.getEntries()) {
            if (cell.getTitle().getPlainText().contains("A") && cell.getCell() != null) {
                if (cell.getCell().getValue().equals(dateFormat.format(date))) {
                    insertLocation = cell.getCell().getRow();
                    break;
                }
                insertLocation = cell.getCell().getRow() + 1;
            }
        }

        CellEntry dateEntry = new CellEntry(insertLocation, 1, dateFormat.format(date));
        cellFeed.insert(dateEntry);
        CellEntry dataEntry = new CellEntry(insertLocation, 2, funds.toString());
        cellFeed.insert(dataEntry);
        CellEntry citizenEntry = new CellEntry(insertLocation, 3, citizens.toString());
        cellFeed.insert(citizenEntry);
        CellEntry fleetEntry = new CellEntry(insertLocation, 4, fleet.toString());
        cellFeed.insert(fleetEntry);

        RSIPledgeWatcher.logger.info("Updated raw sheet with " + NumberFormat.getNumberInstance(Locale.US).format(funds) + " at " + dateTime.format(date));
    }


}