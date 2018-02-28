/*************************************************************************\
* Copyright (C) 2010 The Infinite Kind, LLC
*
* This code is released as open source under the Apache 2.0 License:<br/>
* <a href="http://www.apache.org/licenses/LICENSE-2.0">
* http://www.apache.org/licenses/LICENSE-2.0</a><br />
\*************************************************************************/

package com.moneydance.modules.features.yahooqt;

import com.infinitekind.moneydance.model.*;
import com.infinitekind.util.CustomDateFormat;
import com.infinitekind.util.DateUtil;
import com.moneydance.apps.md.controller.Util;

import java.text.MessageFormat;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Vector;
import java.util.concurrent.Callable;

/**
 * Downloads exchange rates and security prices
 */
public class DownloadTask implements Callable<Boolean> {
  static final String NAME = "DownloadTask";
  
  private static final int FOREX_HISTORY_INTERVAL = 7; // snapshot minimum frequency, in days
  
  private final StockQuotesModel _model;
  private final ResourceProvider _resources;
  private final CustomDateFormat _dateFormat;
  private final SimpleDateFormat _dateTimeFormat;
  private boolean downloadRates = true;
  private boolean downloadPrices = true;

  private float _progressPercent = 0.0f;

  DownloadTask(final StockQuotesModel model, final ResourceProvider resources) {
    _model = model;
    _resources = resources;
    _dateFormat = _model.getPreferences().getShortDateFormatter();
    _dateTimeFormat = new SimpleDateFormat(_dateFormat.getPattern() + " h:mm a");
    downloadRates = model.isExchangeRateSelected();
    downloadPrices = model.isHistoricalPriceSelected();
  }

  public Boolean call() throws Exception {
    boolean ratesResult = true;
    if(downloadRates) {
      ratesResult = downloadExchangeRates();
      if(ratesResult) {
        _model.saveLastExchangeRatesUpdateDate(DateUtil.getStrippedDateInt());
      }
    }
    boolean pricesResult = true;
    if(downloadPrices) {
      pricesResult = downloadPrices();
      if(pricesResult) {
        _model.saveLastQuoteUpdateDate(DateUtil.getStrippedDateInt());
      }
    }
    return ratesResult && pricesResult;
  }
  
  private Boolean downloadExchangeRates() throws Exception {
    _model.showProgress(0.0f, MessageFormat.format(
      _resources.getString(L10NStockQuotes.EXCHANGE_RATES_BEGIN),
      _model.getSelectedExchangeRatesConnection().toString()));
    AccountBook book =  _model.getBook();
    if (book == null) return Boolean.FALSE;
    CurrencyTable ctable = book.getCurrencies();
    // figure out the last date of an update...
    final CurrencyType baseCurrency = ctable.getBaseType();
    final int today = Util.getStrippedDateInt();
    boolean success = true;
    try {
      Vector<CurrencyType> currenciesToCheck = new Vector<>();
      ctable.dumpCurrencies();
      for (CurrencyType ctype : ctable.getAllCurrencies()) {
        if (ctype.getCurrencyType() == CurrencyType.Type.CURRENCY) {
          currenciesToCheck.addElement(ctype);
        }
      }
      float progressPercent = 0.0f;
      final float progressIncrement = currenciesToCheck.isEmpty() ? 1.0f :
                                      100.0f / (float)currenciesToCheck.size();
      Exception downloadException = null;
      for (int i = currenciesToCheck.size() - 1; i >= 0; i--) {
        downloadException = null;
        
        final CurrencyType currencyType = currenciesToCheck.elementAt(i);
        // skip if no conversion necessaryw
        if (baseCurrency.equals(currencyType)) continue;
        
        System.err.println("updating currency: "+currencyType+" ("+currencyType.getTickerSymbol()+")");
        
        BaseConnection connection = null;
        try {
          connection = _model.getSelectedExchangeRatesConnection();
          
          double rate = getRate(currencyType, baseCurrency, connection);
          progressPercent += progressIncrement;
          final String message, logMessage;
          if (rate <= 0.0) {
            message = MessageFormat.format( _resources.getString(L10NStockQuotes.ERROR_EXCHANGE_RATE_FMT),
                                            currencyType.getIDString(),  baseCurrency.getIDString());
            logMessage = MessageFormat.format("Unable to get rate from {0} to {1}",
                                              currencyType.getIDString(),  baseCurrency.getIDString());
          } else {
            message = buildRateDisplayText(currencyType, baseCurrency, rate, today);
            logMessage = buildRateLogText(currencyType, baseCurrency, rate, today);
          }
          _model.showProgress(progressPercent, message);
          if(Main.DEBUG_YAHOOQT) System.err.println(logMessage);
        } catch (Exception error) {
          downloadException = error;
          String message = MessageFormat.format(
            _resources.getString(L10NStockQuotes.ERROR_DOWNLOADING_FMT),
            _resources.getString(L10NStockQuotes.RATES),
            error.getLocalizedMessage());
          _model.showProgress(0f, message);
          if(Main.DEBUG_YAHOOQT) System.err.println(MessageFormat.format("Error while downloading Currency Exchange Rates: {0}",
                                                                         error.getMessage()));
          error.printStackTrace();
          success = false;
        }
        connection.didUpdateItem(currencyType, downloadException);
      }
    } finally {
      ctable.fireCurrencyTableModified();
    }
    
    if (success) {
      SQUtil.pauseTwoSeconds(); // wait a bit so user can read the last rate update
      String message = MessageFormat.format(
        _resources.getString(L10NStockQuotes.FINISHED_DOWNLOADING_FMT),
        _resources.getString(L10NStockQuotes.RATES));
      _model.showProgress(0f, message);
      if(Main.DEBUG_YAHOOQT) System.err.println("Finished downloading Currency Exchange Rates");
    }
    return Boolean.TRUE;
  }
  
  private Boolean downloadPrices() throws Exception {
    final String taskDisplayName = _resources.getString(L10NStockQuotes.QUOTES);
    // this is a Moneydance string that says 'Downloading {acctname}'
    String format = _model.getGUI().getStr("downloading_acct_x");
    _model.showProgress(0.0f, SQUtil.replaceAll(format, "{acctname}", taskDisplayName));

    AccountBook book = _model.getBook();
    if (book == null) {
      if(Main.DEBUG_YAHOOQT) System.err.println("Skipping security prices download, no book account");
      return Boolean.FALSE;
    }
    CurrencyTable ctable = book.getCurrencies();
    final int numDays = _model.getHistoryDays();

    boolean success = false;
    int skippedCount = 0, errorCount = 0, successCount = 0;
    try {
      int totalValues = (int) ctable.getCurrencyCount();
      int currIdx = 0;
      for (CurrencyType currencyType : ctable) {
        _progressPercent = currIdx / (float) totalValues;
        if (_progressPercent == 0.0f) {
          _progressPercent = 0.01f;
        }

        if (currencyType.getCurrencyType() == CurrencyType.Type.SECURITY) {
          DownloadResult result = updateSecurity(currencyType, numDays);
          if (result.skipped) {
            
            ++skippedCount;
          } else {
            if (result.currentError || (result.historyErrorCount > 0)) {
              ++errorCount;
            }
            if (!result.currentError || (result.historyRecordCount > 0)) {
              ++successCount;
            }
            // log any messages for those that weren't skipped
            if(Main.DEBUG_YAHOOQT && !SQUtil.isBlank(result.logMessage)) System.err.println(result.logMessage);
          }
        }
        currIdx++;
      }
      success = true;
    } catch (Exception error) {
      String message = MessageFormat.format(
              _resources.getString(L10NStockQuotes.ERROR_DOWNLOADING_FMT),
              _resources.getString(L10NStockQuotes.QUOTES),
              error.getLocalizedMessage());
      _model.showProgress(0f, message);
      if(Main.DEBUG_YAHOOQT) System.err.println(MessageFormat.format("Error while downloading Security Price Quotes: {0}",
                                                                     error.getMessage()));
      error.printStackTrace();
      success = false;
    } finally {
      _progressPercent = 0f;
      // no longer need to fire this event as calling syncItem on a modified object (like the currency) 
      // will automatically sync it
      //ctable.fireCurrencyTableModified();  
    }

    if (success) {
      SQUtil.pauseTwoSeconds(); // wait a bit so user can read the last price update
      if ((skippedCount == 0) && (errorCount == 0) && (successCount == 0)) {
        String message = MessageFormat.format(
                _resources.getString(L10NStockQuotes.FINISHED_DOWNLOADING_FMT),
                _resources.getString(L10NStockQuotes.QUOTES));
        _model.showProgress(0f, message);
        if(Main.DEBUG_YAHOOQT) System.err.println("Finished downloading Security Price Quotes");
      } else {
        String message = MessageFormat.format(
                _resources.getString(L10NStockQuotes.QUOTES_DONE_FMT),
                Integer.toString(skippedCount), Integer.toString(errorCount),
                Integer.toString(successCount));
        _model.showProgress(0f, message);
        if(Main.DEBUG_YAHOOQT) {
          System.err.println(MessageFormat.format("Security price update complete with {0} skipped, {1} errors and {2} quotes obtained",
                                                  Integer.toString(skippedCount), Integer.toString(errorCount),
                                                  Integer.toString(successCount)));
        }
      }
    }
    return Boolean.TRUE;
  }


  private double getRate(CurrencyType currType, CurrencyType baseType, BaseConnection connection)
    throws Exception
  {
    BaseConnection.ExchangeRate rateInfo =
      connection.getCurrentRate(currType.getIDString(), baseType.getIDString());
    if (rateInfo == null) {
      return -1.0;
    }

    double rate = rateInfo.getRate();
    if (rate <= 0.0)
      return rate;

    int lastDate = 0;
    for (CurrencySnapshot snap : currType.getSnapshots()) {
      lastDate = Math.max(lastDate, snap.getDateInt());
    }

    int today = Util.getStrippedDateInt();
    boolean addSnapshot = Util.incrementDate(lastDate, 0, 0, FOREX_HISTORY_INTERVAL) < today;
    if (addSnapshot) {
      CurrencySnapshot snap = currType.setSnapshotInt(today, rate);
      System.err.println("forex updated snapshot: "+snap);
    }
    currType.setUserRate(rate);
    System.err.println("forex updated current: "+currType);
    return rate;
  }

  private DownloadResult updateSecurity(CurrencyType currType, int numDays) {
    DateRange dateRange = HistoryDateRange.getRangeForSecurity(currType, numDays);
    
    // check if the user is skipping this one but leaving the symbol intact
    DownloadResult result = new DownloadResult();
    Exception downloadException = null;
    result.displayName = currType.getName();
    if (!_model.getSymbolMap().getIsCurrencyUsed(currType)) {
      result.skipped = true;
      return result;
    }

    // not skipping, log what we're downloading
    if(Main.DEBUG_YAHOOQT) System.err.println("Downloading price of "+currType.getName()+" for dates "+dateRange.format(_dateFormat));
    BaseConnection connection = _model.getSelectedHistoryConnection();
    if (connection == null) {
      final String message = _resources.getString(L10NStockQuotes.ERROR_NO_CONNECTION);
      _model.showProgress(_progressPercent, message);
      result.historyErrorCount = 1;
      result.historyResult = message;
      result.currentError = true;
      result.currentResult = message;
      result.logMessage = "No connection established";
      return result;
    }
    
    boolean foundPrice = false;
    double latestRate = 0.0;   // the raw downloaded price in terms of the price currency
    long latestPriceDate = 0;
    BaseConnection priceConnection = null;
    // both check for a supporting connection, and also check for 'do not update'
    if (connection.canGetHistory() && _model.isHistoricalPriceSelected()) {
      connection.setDefaultCurrency();
      try {
        final StockHistory history = connection.getHistory(currType, dateRange, true);
        if (history == null) {
          result.skipped = true;
          result.logMessage = "No history obtained for security " + currType.getName();
          return result;
        }
        
        // we're just counting the number of successful symbols
        if (history.getErrorCount() > 0) result.historyErrorCount = 1;
        if (history.getRecordCount() > 0) {
          result.historyRecordCount = 1;
          result.historyResult = "Success";  // currently not shown to the user
          StockRecord latest = history.findMostRecentValidRecord();
          if (latest != null) {
            foundPrice = true;
            priceConnection = connection;
            latestRate = latest.closeRate;
            // no time conversion needed since historical prices just define date, this will be
            // as of midnight, and thus we're converting to midnight local here
            latestPriceDate = latest.dateTimeGMT;
          }
        } else {
          result.historyResult = "Error";   // currently not shown to the user
          result.logMessage = "No history records returned for security " + currType.getName();
        }
      } catch (DownloadException e) {
        downloadException = e;
        final CurrencyType currency = (e.getCurrency() != null) ? e.getCurrency() : currType;
        String message = MessageFormat.format(
                _resources.getString(L10NStockQuotes.ERROR_HISTORY_FMT),
                currency.getName(), e.getLocalizedMessage());
        _model.showProgress(_progressPercent, message);
        result.historyErrorCount = 1;
        result.historyResult = e.getMessage();
        if (SQUtil.isBlank(result.logMessage)) {
          result.logMessage = MessageFormat.format("Error downloading historical prices for {0}: {1}",
                  currency.getName(), e.getMessage());
        }
      }
      
      connection.didUpdateItem(currType, downloadException);
    } // if getting price history and connection is not 'do not update'
    
    
    // update the current price if possible, the last price date is stored as a long
    final long lastUpdateDate = currType.getLongParameter("price_date", 0);
    // for now we're going to skip the time check because it introduces too many glitches and
    // unexpected behavior, mainly because the Currency/Security History Window will update the
    // time to the local time, often newer than the downloaded time
    boolean currentPriceUpdated = foundPrice; // && (storedCurrentPriceDate < latestPriceDate);
    final CurrencyType priceCurrency = getPriceCurrency(currType, priceConnection);
    if (priceCurrency == null) {
      // error condition
      final String message = "Error: could not determine the price currency, skipping current price update";
      if(Main.DEBUG_YAHOOQT) System.err.println(message);
      if (SQUtil.isBlank(result.logMessage)) {
        result.logMessage = message;
      }
      currentPriceUpdated = false;
      foundPrice = false;
    }
    if (currentPriceUpdated) {
      // the user rate should be stored in terms of the base currency, just like the snapshots
      currType.setUserRate(latestRate, priceCurrency);
      currType.setParameter("price_date", latestPriceDate);
      currType.syncItem();
      System.err.println("security updated current: "+currType);
      if (!SQUtil.isBlank(result.logMessage)) {
        // the historical price has a log message already, so just dump the current price update
        // log message now
        if(Main.DEBUG_YAHOOQT) {
          System.err.println(buildPriceLogText(priceCurrency, result.displayName,
                                               latestRate, latestPriceDate, currentPriceUpdated));
        }
      }
    } else {
      System.err.println("No current price found for "+result.displayName);
    }
    if (foundPrice) {
      // use whichever connection was last successful at getting the price to show the value
      _model.showProgress(_progressPercent,
                          buildPriceDisplayText(priceCurrency, result.displayName,
                                                latestRate, latestPriceDate));
      if (SQUtil.isBlank(result.logMessage)) {
        result.logMessage = buildPriceLogText(priceCurrency, result.displayName,
                latestRate, latestPriceDate, currentPriceUpdated);
      }
    }
    return result;
  }

  private CurrencyType getPriceCurrency(CurrencyType securityCurrency, BaseConnection priceConnection) {
    if (priceConnection == null) { // Essentially an unexpected error condition
      return null;
    }
    // normal condition - the stock exchange will specify the price currency
    return priceConnection.getPriceCurrency(securityCurrency);
  }


  private String buildRateDisplayText(CurrencyType fromCurrency, CurrencyType toCurrency,
                                      double rate, int date) {
    String format = _resources.getString(L10NStockQuotes.EXCHANGE_RATE_DISPLAY_FMT);
    // get the currency that the prices are specified in
    long amount = (rate == 0.0) ? 0 : fromCurrency.getLongValue(1.0 / rate);
    final char decimal = _model.getPreferences().getDecimalChar();
    String priceDisplay = fromCurrency.formatFancy(amount, decimal);
    String asofDate =_dateFormat.format(date);
    return MessageFormat.format(format, fromCurrency.getIDString(), toCurrency.getIDString(),
                                asofDate, priceDisplay);
  }

  private String buildRateLogText(CurrencyType fromCurrency, CurrencyType toCurrency,
                                  double rate, int date) {
    String format = "Exchange Rate from {0} to {1} as of {2}: {3}";
    // get the currency that the prices are specified in
    long amount = (rate == 0.0) ? 0 : fromCurrency.getLongValue(1.0 / rate);
    String priceDisplay = fromCurrency.formatFancy(amount, '.');
    String asofDate = _dateFormat.format(date);
    return MessageFormat.format(format, fromCurrency.getIDString(), toCurrency.getIDString(),
                                asofDate, priceDisplay);
  }
  
  private String buildPriceDisplayText(CurrencyType priceCurrency, String name, double rate, long dateTime) {
    String format = _resources.getString(L10NStockQuotes.SECURITY_PRICE_DISPLAY_FMT);
    long amount = (rate == 0.0) ? 0 : priceCurrency.getLongValue(1.0 / rate);
    final char decimal = _model.getPreferences().getDecimalChar();
    String priceDisplay = priceCurrency.formatFancy(amount, decimal);
    String asofDate =_dateFormat.format(new Date(dateTime));
    return MessageFormat.format(format, name, asofDate, priceDisplay);
  }

  private String buildPriceLogText(CurrencyType priceCurrency,
                                   String name, double rate, long dateTime, boolean updated) {
    String format = updated ? "Current price for {0} as of {1}: {2}" : "Latest historical price for {0} as of {1}: {2}";
    long amount = (rate == 0.0) ? 0 : priceCurrency.getLongValue(1.0 / rate);
    String priceDisplay = priceCurrency.formatFancy(amount, '.');
    final String asofDate;
    if (updated) {
      // the current price can be intra-day, so log the date and time of the price update.
      asofDate = _dateTimeFormat.format(new Date(dateTime));
    } else {
      asofDate = _dateFormat.format(new Date(dateTime));
    }
    return MessageFormat.format(format, name, asofDate, priceDisplay);
  }

}