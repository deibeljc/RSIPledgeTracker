package main;

import com.google.gdata.util.ServiceException;
import enums.InsertionType;
import logging.Logging;
import org.json.JSONObject;
import sheets.SpreadsheetServices;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.NumberFormat;
import java.util.Date;
import java.util.Locale;
import java.util.logging.Logger;

/**
 * DISCLAIMER: This is a bit of a mess as It was just a little testing class for now.
 * It might be a bit of a pain to read through, but it isn't a ton of stuff :).
 */
public class RSIPledgeWatcher {

    private static int timeInMinutes;
    private static boolean reportOnlyIfChanged = false;
    private static Long[] parsedData;
    private static Long previousFundAmount;
    public static String sheetName = "Crowdfunding Data";
    public static String workSheetName = "daily updated pledge-watch";
    public static InsertionType type;
    public static Logger logger;

    /**
     * The main logic of the application. It will get the basic user input and then create a continuous loop
     * that will grab the current funds from the RSI API and do some reporting.
     * @param args
     * @throws IOException
     * @throws InterruptedException
     */
    public static void main(String[] args) throws IOException, InterruptedException, ServiceException {
        // Get how long (in minutes) to check.
        String s = System.getProperty("pollTime");
        String localSheetName = System.getProperty("sheetName");
        sheetName = localSheetName != null ? localSheetName : sheetName;

        // This will set what worksheet to use within the spreadsheet
        String localWorkSheetName = System.getProperty("workSheetName");
        workSheetName = localWorkSheetName != null ? localWorkSheetName : workSheetName;

        // The insertion type
        type = InsertionType.valueOf(System.getProperty("type"));

        // Init logger after the type has been set.
        logger = Logging.initializeLogger();

        logger.info(type.name());

        timeInMinutes = Integer.parseInt(s);


        while (true) {
            parsedData = getFundsFromJson(executePost("https://robertsspaceindustries.com/api/stats/getCrowdfundStats",
                    "{\"chart\":\"day\",\"fans\":true,\"funds\":true,\"fleet\":true,\"alpha_slots\":true}"));

            if (reportOnlyIfChanged && parsedData.equals(previousFundAmount)) {
                RSIPledgeWatcher.logger.info("Not reporting because the amount of funds has not changed");
                Thread.sleep(timeInMinutes * 1000 * 60);
                continue;
            }

            previousFundAmount = parsedData[0];
            // get current date time with Date()
            Date date = new Date();
            // Output the results.
            RSIPledgeWatcher.logger.info("Amount funded $" + NumberFormat.getNumberInstance(Locale.US).format(parsedData[0] / 100.00));
            // Actually update the results
            updateSheet(parsedData[0] / 100.00, date, parsedData[1], parsedData[2], type);
            // Sleep the main thread until it is time to kick it up again :D
            Thread.sleep(timeInMinutes * 1000 * 60);
        }
    }

    /**
     * Returns the amount of Funds from the json object retrieved from robertsspaceindustries.com. It will return
     * the dollar amount + the cents concatenated.
     * @param json
     * @return
     */
    private static Long[] getFundsFromJson(String json) {
        // Create the JSON Object
        JSONObject jsonObject = new JSONObject(json).getJSONObject("data");
        // 0: Funds, 1: Citizens, 2: Fleet
        Long[] returnValues = {jsonObject.getLong("funds"), jsonObject.getLong("fans"), jsonObject.getLong("fleet")};
        // Return the array.
        return returnValues;
    }

    /**
     * This just calls the actual updating method.
     * @param data the funds
     * @param citizens the citizens
     * @param fleet the fleet
     * @param date the date
     * @param type the insertion enum type.
     * @throws ServiceException
     * @throws IOException
     */
    private static void updateSheet(Double data, Date date, Long citizens, Long fleet, InsertionType type) throws ServiceException, IOException, InterruptedException {
        SpreadsheetServices.updateSheetsWithData(data, date, citizens, fleet, type, 1);
    }

    /**
     * This will create the post request to RSI's API. It spoofs the origin to act like it came from RSI which
     * gives it access to the endpoint for funds and other simple graph data.
     * @param targetURL the URL endpoint to make the request to
     * @param urlParameters the request body
     * @return
     */
    private static String executePost(String targetURL, String urlParameters) {
        HttpURLConnection connection = null;
        try {
            //Create connection
            URL url = new URL(targetURL);
            connection = (HttpURLConnection)url.openConnection();
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            connection.setRequestProperty("Origin", "https://robertsspaceindustries.com");
            connection.setRequestProperty("Content-Length", Integer.toString(urlParameters.getBytes().length));
            connection.setRequestProperty("Content-Language", "en-US");

            connection.setUseCaches(false);
            connection.setDoOutput(true);

            //Send request
            DataOutputStream wr = new DataOutputStream (connection.getOutputStream());
            wr.writeBytes(urlParameters);
            wr.close();

            //Get Response
            InputStream inputStream = connection.getInputStream();
            BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(inputStream));
            StringBuilder response = new StringBuilder(); // or StringBuffer if not Java 5+
            String line;
            while((line = bufferedReader.readLine()) != null) {
                response.append(line);
                response.append('\r');
            }
            bufferedReader.close();
            return response.toString();
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        } finally {
            if(connection != null) {
                connection.disconnect();
            }
        }
    }
}
