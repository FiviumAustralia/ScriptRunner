package com.fivium.scriptrunner2.script.parser;


import com.fivium.scriptrunner2.Logger;
import com.fivium.scriptrunner2.ex.ExParser;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


/**
 * A parser for splitting strings of one or more statements into {@link ParsedStatement}s, which are in turn composed of
 * indiviudal {@link ScriptSegment}s. This class contains no state and does not need to be instantiated.
 */
public class ScriptParser {
  
  /** The pattern which delimits statements, currently a "/" on its own line. If this changes the statementDelimiterCharSearch method must also change to reflect this.  */
  static final Pattern STATEMENT_DELIMETER_PATTERN = Pattern.compile("^[ \\t]*/[ \\t]*$", Pattern.MULTILINE);
  static final Pattern PLSQL_ANON_BLOCK_TERMINATOR = Pattern.compile("END.*;");
  
  /**
   * Character sequences which delimit escaped SQL segments.
   */
  enum EscapeDelimiter {    
    //Note that order is important: q-quotes before single quotes (otherwise single quote would match first in a search for q-quote end sequence)
    QQUOTE_BRACE("q'{", "}'", true),
    QQUOTE_SQUARE("q'[", "]'", true),
    QQUOTE_BANG("q'!", "!'", true),
    QQUOTE_PAREN("q'(", ")'", true),
    QQUOTE_ANGLE("q'<", ">'", true),
    COMMENT_MULTILINE("/*", "*/", true),
    DOUBLE_QUOTE("\"", "\"", true),
    SINGLE_QUOTE("'", "'", true),
    COMMENT_SINGLELINE("--", "\n", false); //possible problem here, will not work for files with Mac-only (\r) line endings
    
    final String mStartSequence;
    final String mEndSequence;
    final boolean mRequiresEndDelimiter;
    
    private EscapeDelimiter(String pStartSequence, String pEndSequence, boolean pRequiresEndDelimiter){
      mStartSequence = pStartSequence;
      mEndSequence = pEndSequence;
      mRequiresEndDelimiter = pRequiresEndDelimiter;
    }
    
    public String toString(){
      return mStartSequence;
    }
  }
  
  /**
   * Tests a string buffer for a statement delimiter at the given index. If the character at the index is the delimiter
   * character, the contents of the line the character is on is determined. If the line contents represents a statement delimiter,
   * the method returns true.<br/><br/>
   * A statement delimiter is considered to be the "/" character on a line by itself, disregarding whitespace.<br/><br/>
   * This method acts as a replacement for running the expensive STATEMENT_DELIMETER_PATTERN regular expresison when
   * parsing a script - this was causing performance issues.
   * @param pBuffer String to test.
   * @param pAtIndex Index of character to test.
   * @return True if this character is a new
   */
  static boolean statementDelimiterCharSearch(String pBuffer, int pAtIndex){
    
    //Don't bother searching if we're not starting from a delimiter character
    if(pBuffer.charAt(pAtIndex) != '/'){
      return false;
    }
    
    //Find the last newline before the current position
    int lLastNewlineBefore = pBuffer.substring(0, pAtIndex).lastIndexOf('\n');
    //Find the first newline after the current position
    int lFirstNewlineAfter = pBuffer.indexOf('\n', pAtIndex);
    
    //If we're at the start of the string
    if(lLastNewlineBefore == -1){
      lLastNewlineBefore = 0;
    }
    
    //If we're at the end of the string
    if(lFirstNewlineAfter == -1){
      lFirstNewlineAfter = pBuffer.length();
    }
    
    //The line contents is anything between the two newlines
    String lLine = pBuffer.substring(lLastNewlineBefore, lFirstNewlineAfter);
    
    //Trim the line and test for the delimiter
    return "/".equals(lLine.trim());    
  }
  
  private ScriptParser() {}
  
  /**
   * Splits a single string containing one or more delimited SQL scripts into a list of individual statements, represented
   * as {@link ParsedStatement}s. The splitter performs a basic parse of the string to account for the following Oracle SQL 
   * escape sequences:
   * <ul>
   * <li>Single quote (string literal)</li>
   * <li>Double quote (identifier)</li>
   * <li>Q-quoted string (e.g. <tt>q'{What's up}'</tt>)</li>
   * <li>Single line comment (--)</li>
   * <li>Multi line comment</li>
   * </ul>
   * Script delimeters within escape sequences are not used to split the script. The delimiter used is a single forward slash
   * on an otherwise empty line. This mirrors the Oracle SQL*Plus client syntax.
   * @param pScript Script to split.   
   * @param pAllowSemicolonTerminators Whether or not semicolons are allowed to terminate 
   * @return List of nested statements.
   * @throws ExParser If an escape sequence isn't terminated or if EOF is reached and unterminated input remains.
   */
  public static List<ParsedStatement> parse(String pScript, boolean pAllowSemicolonTerminators)
  throws ExParser {
    BufferedReader lRemainingScriptBufferedReader = new BufferedReader(new StringReader(pScript));

    List<ParsedStatement> lStatementList = new ArrayList<ParsedStatement>();
    
    long lTimerOverallStart = System.currentTimeMillis();
    int lStatementCount = 0;
    Logger.logDebug("Parsing script");

    long lTimerStatementStart = System.currentTimeMillis();

    String lCurrentLine = null;
    StringBuilder lScriptBuilder = null;
    int lLineNumber = 0;
    int lStatementLineNumber = 0; // Holds the line number where a statement starts.
    // Loop through the files one line at a time creating UnescapedTextSegments as we go until a '/' is encountered.
    // At which point, add it to the list of ParsedStatements.
    try {
      while ((lCurrentLine = lRemainingScriptBufferedReader.readLine()) != null) {
        lLineNumber++;
        if (lCurrentLine.replaceAll("\\s+$","").equals("/")) {
          UnescapedTextSegment lUnescapedTextSegment = new UnescapedTextSegment(0);
          lUnescapedTextSegment.setContents(lScriptBuilder.toString());
          List<ScriptSegment> lCurrentStatementSegments = new ArrayList<ScriptSegment>();
          lCurrentStatementSegments.add(lUnescapedTextSegment);

          ParsedStatement lParsedStatement = new ParsedStatement(lCurrentStatementSegments);
          String lUpperStatementString = lParsedStatement.getStatementString().toUpperCase();

          // Get the last line of the file and set up a regex to see if it ends with END <identifier>; which is a
          // valid case for a semi-colon terminator.
          String[] lUpperStatementStringParts = lUpperStatementString.split("\n");
          String lLastLine = lUpperStatementStringParts[lUpperStatementStringParts.length-1];
          Matcher lIsAnonBlockMatcher = PLSQL_ANON_BLOCK_TERMINATOR.matcher(lLastLine);

          // If the statement ends with a semi-colon and is not a PL/SQL anon block then raise an exception.
          if (!pAllowSemicolonTerminators && lUpperStatementString.endsWith(";") && !lIsAnonBlockMatcher.find()) {
            throw new ExParser("Statement at line " + lStatementLineNumber + " ends with a semi-colon but it is not an anonymous block. ScriptRunner only interprets \"/\" as a statement terminator. Only the first error in this patch has been reported - there may be others.\n" + lParsedStatement.getStatementString());
          }

          lStatementList.add(lParsedStatement);
          Logger.logDebug("Parsed statement " +  ++lStatementCount + " in " + (System.currentTimeMillis() - lTimerStatementStart + " ms"));
          lTimerStatementStart = System.currentTimeMillis();
          lScriptBuilder = null;
        } else {
          if (lScriptBuilder == null) {
            lScriptBuilder = new StringBuilder();
            lStatementLineNumber = lLineNumber;
          }
          lScriptBuilder.append(lCurrentLine + "\n");
        }
      }
    } catch (IOException ex) {
      throw new ExParser("Failed to read script ");
    }

    // If the script still has text beyond the final delimiter and that text is not just whitespace then error.
    if (lScriptBuilder != null  && !lScriptBuilder.toString().trim().equals("") ) {
      throw new ExParser("Could not parse patch: Undelimited input still in buffer at end of file:\n" + lScriptBuilder.toString());
    }
    
    Logger.logDebug("Script parse complete in " + (System.currentTimeMillis() - lTimerOverallStart) + " ms");

    return lStatementList;    
  }
}
