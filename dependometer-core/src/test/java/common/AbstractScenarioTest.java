package common;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileFilter;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import junit.framework.TestCase;

import org.apache.log4j.Logger;
import org.junit.Assume;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

import common.HTMLTableParser.HTMLRow;

/**
 * This class implements JUnit test that runs all Dependometer test examples contained in the directory
 * target/test-dependencies. The metrics to test are extracted from testconfig.xml. Each testrun performs the following
 * steps: 1. Run the Dependometer as a spawned process. 2. Load the definitions of the metrics to be tested from the
 * file �testconfig.xml� 3. Verify the contents of the report files generated by the Dependometer-run against the
 * metrics to be tested.
 *
 * This test depends on maven directory structure and maven (surfire) system properties (classpath). It will only run in
 * the given project structure.
 *
 */
public abstract class AbstractScenarioTest extends TestCase
{
  private final ArrayList<String> pageNames = new ArrayList<String>();

  private final ArrayList<Set<PageMetric>> pageMetrics = new ArrayList<Set<PageMetric>>();

  private final ArrayList<ArrayList<String>> pageVerifyTexts = new ArrayList<ArrayList<String>>();

  protected File testScenarioRootDir;

   protected static Logger logger = Logger.getLogger(AbstractScenarioTest.class);

   /** directory containing the test projects, relative to project directory. */
   private static final String TEST_DIR = "target/test-dependencies";

   /** report directory, relative to the test projects (inside TEST_DIR). */
   private static final String REPORT_DIR = "results/dependometer";

   /** template directory, containing index.htm, logo.gif and stylesheet. */
  protected static final String TEMPLATE_DIR = "src/test/result-template";

   @Override
   protected void setUp() throws Exception
   {
      super.setUp();

      testScenarioRootDir = new File(TEST_DIR);
      if (!testScenarioRootDir.exists() || !testScenarioRootDir.isDirectory())
      {
         fail(testScenarioRootDir + " is not a directory - test must be executed in the projects root dir");
      }

      logger.debug("Working directory is " + System.getProperty("user.dir"));
   }

  protected void copyReportDirectory(File sourceLocation, File targetLocation) throws IOException   {
      logger.debug("Copy report file '" + sourceLocation.getAbsolutePath() + " to " + targetLocation.getAbsolutePath());

      // create FileFilter for .svn directories
      FilenameFilter ff = new FilenameFilter()
      {
         public boolean accept(File dir, String name)
         {
            return !name.startsWith(".svn");
         }
      };
      // If targetLocation does not exist, it will be created.
      if (sourceLocation.isDirectory())
      {
         if (!targetLocation.exists())
         {
            targetLocation.mkdir();
         }

         String[] children = sourceLocation.list(ff);
         for (int i = 0; i < children.length; i++)
         {
            copyReportDirectory(new File(sourceLocation, children[i]), new File(targetLocation, children[i]));
         }
      }
      else
      {

         InputStream in = new FileInputStream(sourceLocation);
         OutputStream out = new FileOutputStream(targetLocation);

         // Copy the bits from instream to outstream
         byte[] buf = new byte[1024];
         int len;
         while ((len = in.read(buf)) > 0)
         {
            out.write(buf, 0, len);
         }
         in.close();
         out.close();
      }
   }

  protected void execDependometer(File testCaseDir) throws IOException, InterruptedException   {
      // run dependometer in a new process - dependometer cannot run twice
      // inside the same VM.
      List<String> command = new LinkedList<String>();
      command.add("java");
      command.add("-ea");
      String sureFireCp = System.getProperty("surefire.test.class.path");
      command.add("-classpath");
      if (sureFireCp != null)
      {
         command.add(sureFireCp);
      }
      else
      {
         command.add(System.getProperty("java.class.path"));
      }

      command.add("com.valtech.source.dependometer.ui.console.Main");
      command.add("-file");
      command.add("dep.xml");

      ProcessBuilder pb = new ProcessBuilder();

      logger.debug("Excuting command line: " + command);
      logger.debug("working directory is '" + testCaseDir.getAbsolutePath() + "'");

      pb.command(command);
      pb.directory(testCaseDir);
      pb.redirectErrorStream(true);

      Process p = pb.start();

      InputStreamReader reader = new InputStreamReader(p.getInputStream());
      BufferedReader buf_reader = new BufferedReader(reader);
      String line;
      while ((line = buf_reader.readLine()) != null)
      {
         logger.debug(line);
      }

      // wait for Dependometer to finish
      int returnCode = p.waitFor();

      if (returnCode != 0)
      {
         fail("Dependometer did not execute normally! Return code = " + returnCode
            + ". Look in debug statements for details.");
      }
   }

  private void checkResult(File testCaseDir) throws ParserConfigurationException, SAXException, IOException {
      // clear hash maps
      pageNames.clear();
      pageMetrics.clear();
      pageVerifyTexts.clear();

      // read metrics to test into hash-map
      XMLReader metricsFile = new XMLReader();
      logger.debug("reading expected results from " + testCaseDir + "/testconfig.xml");
      try
      {
         metricsFile.parseMetrics(testCaseDir + "/testconfig.xml");
      }
      catch (FileNotFoundException e)
      {
         logger.warn("No testconfig.xml with expected results defined");
         return;
      }

      // parse html result file for metric-value pairs in hash-map
      for (int p = 0; p < pageNames.size(); p++)
      {
         String scanFileName = pageNames.get(p);
         File scanFile = new File(testCaseDir, REPORT_DIR + "/" + scanFileName);
         if (!scanFile.exists())
         {
            fail(scanFile.getAbsolutePath() + "could not be found!");
         }

         if (logger.isDebugEnabled())
         {
            logger.debug("Checking file: " + scanFile.getAbsolutePath());
         }

         ArrayList<String> pageTextsToVerify = pageVerifyTexts.get(p);

         verifyTexts(scanFile, pageTextsToVerify);

         verifyMetrics(scanFile, pageMetrics.get(p));
      }
   }

   private void verifyMetrics(File scanFile, Set<PageMetric> metricsToTest) throws MalformedURLException, IOException
   {
      if (metricsToTest.size() > 0)
      { // check if calculated metric values
         // are correct

         logger.debug("parsing metrics in file '" + scanFile.getName() + "':");
         URL url = scanFile.toURI().toURL();

         HTMLTableParser tblParser = new HTMLTableParser();
         tblParser.parse(url);

         for (Object table : tblParser)
         {
            if (metricsToTest.size() > 0)
            {
               checkMetricInHTMLTable((HTMLTableParser.HTMLTable)table, metricsToTest);
            }
         }

         for (PageMetric p : metricsToTest)
         {
            fail("Following metric could not be found in " + scanFile.getName() + ": " + p);
         }

      }
   }

   private void verifyTexts(File scanFile, ArrayList<String> pageTextsToVerify) throws IOException
   {
      if (pageTextsToVerify.size() > 0)
      { // check if text is present in
         // file
         logger.debug("parsing text in file '" + scanFile.getName() + "':");
         // read file contents into string
         String content = "";
         FileInputStream fis = null;
         try
         {
            fis = new FileInputStream(scanFile);
            int x = fis.available();
            byte b[] = new byte[x];
            fis.read(b);
            content = new String(b);
         }
         finally
         {
            if (fis != null)
            {
               try
               {
                  fis.close();
               }
               catch (IOException e1)
               {
                  e1.printStackTrace();
               }
            }
         }
         for (String textToVerify : pageTextsToVerify)
         {
            assertTrue("expected result string not found: '" + textToVerify + "' in result file '"
               + scanFile.getAbsolutePath() + "'\ncontent was " + content, content.contains(textToVerify));
            logger.debug("\tverified text '" + textToVerify + "'");
         }
      }
   }

   // TODO refactor me, I'm error-prone

   /*
    * scans HTML Table for metrics in metricsToTest and removes element if found
    */

   private void checkMetricInHTMLTable(HTMLTableParser.HTMLTable table, Set<PageMetric> metricsToTest)
   {
      if (metricsToTest.size() == 0)
      {
         return;
      }

      for (HTMLRow tableRow : table.rows)
      {
         if (metricsToTest.size() == 0)
         {
            break;
         }
         // now test if first cell in row equals testmetrics
         Object firstCell = tableRow.get(0);
         if (!(firstCell instanceof HTMLTableParser.HTMLTable))
         {
            for (PageMetric pageMetric : metricsToTest)
            {
               if (pageMetric.tablename == null || pageMetric.tablename.equals(table.tableName))
               {
                  if (pageMetric.name.equals(firstCell))
                  {
                     checkMetricValue(pageMetric, tableRow);
                     metricsToTest.remove(pageMetric);
                     break; // exit loop
                  }
               }
            }
         }
         else
         {
            for (Object cell : tableRow)
            {
               if (cell instanceof HTMLTableParser.HTMLTable)
               {
                  checkMetricInHTMLTable((HTMLTableParser.HTMLTable)cell, metricsToTest);
               }
            }
         }
      }
   }

   private void checkMetricValue(PageMetric pageMetric, HTMLRow tableRow)
   {
      if (pageMetric.tablename != null)
      {
         assertEquals("metric: '" + pageMetric.tablename + ":" + pageMetric.name + "'", pageMetric.value, tableRow
            .get(2));
         logger.debug("\tverified metric '" + pageMetric.tablename + ":" + pageMetric.name + "' = (" + pageMetric.value
            + ")");
      }
      else
      {
         assertEquals("metric: '" + pageMetric.name + "'", pageMetric.value, tableRow.get(2));
         logger.debug("\tverified metric '" + pageMetric.name + "' = (" + pageMetric.value + ")");
      }
   }

   class XMLReader
   {

      public void parseMetrics(String filename) throws ParserConfigurationException, SAXException, IOException
      {
         File file = new File(filename);
         DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();

         dbf.setValidating(true);
         dbf.setIgnoringComments(true);

         DocumentBuilder db = dbf.newDocumentBuilder();
         db.setErrorHandler(new org.xml.sax.ErrorHandler()
         {
            // Ignore the fatal errors
            public void fatalError(SAXParseException e) throws SAXException
            {
               logger.error(e.getMessage());
               throw e;
            }

            // Validation errors
            public void error(SAXParseException e) throws SAXParseException
            {
               logger.error(e.getMessage());
               throw e;
            }

            // Show warnings
            public void warning(SAXParseException e) throws SAXParseException
            {
               logger.error(e.getMessage());
               throw e;
            }
         });
         Document doc = db.parse(file);
         doc.getDocumentElement().normalize();

         NodeList pageNodes = doc.getElementsByTagName("pagemetrics");
         for (int p = 0; p < pageNodes.getLength(); p++)
         {
            Node pageNode = pageNodes.item(p);
            Set<PageMetric> pageMetricsToTest = new LinkedHashSet<PageMetric>();
            ArrayList<String> pageTextsToVerify = new ArrayList<String>();
            pageMetrics.add(pageMetricsToTest);
            pageVerifyTexts.add(pageTextsToVerify);
            for (Node childNode = pageNode.getFirstChild(); childNode != null; childNode = childNode.getNextSibling())
            {
               if (childNode.getNodeType() == Node.ELEMENT_NODE)
               {
                  if (childNode.getNodeName().equalsIgnoreCase("scanfile"))
                  {
                     pageNames.add(childNode.getChildNodes().item(0).getNodeValue().trim());
                  }
                  else if (childNode.getNodeName().equalsIgnoreCase("verifytext"))
                  {
                     pageTextsToVerify.add(childNode.getChildNodes().item(0).getNodeValue().trim());
                  }
                  else if (childNode.getNodeName().equalsIgnoreCase("metric"))
                  {
                     Element nodeElement = (Element)childNode;
                     boolean singleLine = "true".equals(nodeElement.getAttribute("singleline"));

                     Element metricElement;
                     String tablename = null;
                     metricElement = (Element)nodeElement.getElementsByTagName("tablename").item(0);
                     if (metricElement != null)
                     {
                        tablename = metricElement.getChildNodes().item(0).getNodeValue();
                     }
                     metricElement = (Element)nodeElement.getElementsByTagName("name").item(0);
                     String name = metricElement.getChildNodes().item(0).getNodeValue().trim();

                     metricElement = (Element)nodeElement.getElementsByTagName("value").item(0);
                     String value = metricElement.getChildNodes().item(0).getNodeValue().trim();

                     PageMetric metric = new PageMetric(tablename, name, value, singleLine);
                     if (pageMetricsToTest.contains(metric))
                     {
                        fail("metric " + metric + " already defined as expected result!");
                     }
                     pageMetricsToTest.add(metric);
                  }
               }
            }
         }
      }
   }

   class PageMetric
   {
      String tablename;

      String name;

      String value;

      boolean singleline;

      public PageMetric(String tablename, String name, String value, boolean singleline)
      {
         this.tablename = tablename;
         this.name = name;
         this.value = value;
         this.singleline = singleline;
      }

      public String toString()
      {
         return "[" + (tablename == null ? "" : tablename + "::") + name + "]";
      }

      @Override
      public boolean equals(Object o)
      {
         if (o == null || !(o instanceof PageMetric))
         {
            return false;
         }

         PageMetric other = (PageMetric)o;

         boolean match = String.valueOf(this.tablename).equals(String.valueOf(other.tablename));
         match &= String.valueOf(this.name).equals(String.valueOf(other.name));
         return match;
      }

      @Override
      public int hashCode()
      {
         return (String.valueOf(tablename) + String.valueOf(name)).hashCode();
      }

   }

   protected void testScenario(final String scenarioDirName)
   {
      File[] dirs = testScenarioRootDir.listFiles(new FileFilter()
      {
         public boolean accept(File file)
         {
            return file.isDirectory() && file.getName().equals(scenarioDirName);
         }
      });
      if (dirs == null || dirs.length == 0)
      {
         fail("test scenario '" + scenarioDirName + "' not found!");
      }

      File scenarioDir = dirs[0];
      Assume.assumeNotNull(scenarioDir);

      logger.info("PROCESSING TEST SCENARIO '" + scenarioDirName + "'");

      try
      {
         copyReportDirectory(new File(TEMPLATE_DIR), new File(scenarioDir, "/results"));
         execDependometer(scenarioDir);
         checkResult(scenarioDir);
      }
      catch (Exception e)
      {
         e.printStackTrace();
         fail("test failed for test dir: '" + scenarioDir.getAbsolutePath());
      }
   }
}
