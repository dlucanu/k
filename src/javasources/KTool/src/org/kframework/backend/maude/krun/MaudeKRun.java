// Copyright (c) 2013-2014 K Team. All Rights Reserved.
package org.kframework.backend.maude.krun;

import org.apache.commons.collections15.Transformer;
import org.kframework.backend.maude.MaudeFilter;
import org.kframework.compile.utils.RuleCompilerSteps;
import org.kframework.kil.*;
import org.kframework.kil.loader.Context;
import org.kframework.krun.runner.KRunner;
import org.kframework.krun.K;
import org.kframework.krun.KRunExecutionException;
import org.kframework.krun.SubstitutionFilter;
import org.kframework.krun.XmlUtil;
import org.kframework.krun.api.*;
import org.kframework.utils.Stopwatch;
import org.kframework.utils.StringUtil;
import org.kframework.utils.errorsystem.KException;
import org.kframework.utils.errorsystem.KException.ExceptionType;
import org.kframework.utils.errorsystem.KException.KExceptionGroup;
import org.kframework.utils.general.GlobalSettings;
import org.kframework.utils.file.FileUtil;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import edu.uci.ics.jung.graph.DirectedGraph;
import edu.uci.ics.jung.graph.DirectedOrderedSparseMultigraph;
import edu.uci.ics.jung.graph.DirectedSparseGraph;
import edu.uci.ics.jung.io.GraphIOException;
import edu.uci.ics.jung.io.graphml.EdgeMetadata;
import edu.uci.ics.jung.io.graphml.GraphMetadata;
import edu.uci.ics.jung.io.graphml.GraphMLReader2;
import edu.uci.ics.jung.io.graphml.HyperEdgeMetadata;
import edu.uci.ics.jung.io.graphml.NodeMetadata;

import java.io.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.Set;


public class MaudeKRun implements KRun {
    protected Context context;
    protected Stopwatch sw;
    
    public MaudeKRun(Context context, Stopwatch sw) {
        this.context = context;
        this.sw = sw;
    }

    private boolean ioServer = K.io;

    public void setBackendOption(String key, Object value) {
        if (key.equals("io")) {
            ioServer = (Boolean) value;
        }
    }
    
    private void executeKRun(StringBuilder maudeCmd) throws KRunExecutionException {
        FileUtil.save(K.maude_in, maudeCmd);
        File outFile = FileUtil.createFile(K.maude_out);
        File errFile = FileUtil.createFile(K.maude_err);

        int returnValue;
        try {
            if (K.log_io) {
                KRunner.main(new String[]{"--maudeFile", K.compiled_def + K.fileSeparator + "main.maude", "--moduleName", K.main_module, "--commandFile", K.maude_in, "--outputFile", outFile.getCanonicalPath(), "--errorFile", errFile.getCanonicalPath(), "--createLogs"},
                        context);
            }
            if (!ioServer) {
                returnValue = KRunner.main(new String[] { "--maudeFile", K.compiled_def + K.fileSeparator + "main.maude", "--moduleName", K.main_module, "--commandFile", K.maude_in, "--outputFile", outFile.getCanonicalPath(), "--errorFile", errFile.getCanonicalPath(), "--noServer" },
                        context);
            } else {
                returnValue = KRunner.main(new String[] { "--maudeFile", K.compiled_def + K.fileSeparator + "main.maude", "--moduleName", K.main_module, "--commandFile", K.maude_in, "--outputFile", outFile.getCanonicalPath(), "--errorFile", errFile.getCanonicalPath() },
                        context);
            }
            if (errFile.exists()) {
                String content = FileUtil.getFileContent(K.maude_err);
                if (content.length() > 0) {
                    throw new KRunExecutionException(content);
                }
            }
            if (returnValue != 0) {
                org.kframework.utils.Error.report("Maude returned non-zero value: " + returnValue);
            }
        } catch (IOException e) {
            if (context.globalOptions.debug) {
                e.printStackTrace();
            }
            GlobalSettings.kem.register(new KException(ExceptionType.ERROR, KExceptionGroup.INTERNAL, 
                    "IO error detected when calling maude"));
        }
    }

    public KRunResult<KRunState> run(Term cfg) throws KRunExecutionException {
        MaudeFilter maudeFilter = new MaudeFilter(context);
        maudeFilter.visitNode(cfg);
        StringBuilder cmd = new StringBuilder();

        if(K.trace) {
            cmd.append("set trace on .").append(K.lineSeparator);
        }
        if(K.profile) {
            cmd.append("set profile on .").append(K.lineSeparator);
        }

        cmd.append("set show command off .").append(K.lineSeparator)
            .append(setCounter()).append(K.maude_cmd).append(" ")
            .append(maudeFilter.getResult()).append(" .").append(K.lineSeparator);

        if(K.profile) {
            cmd.append("show profile .").append(K.lineSeparator);
        }

        cmd.append(getCounter());

        executeKRun(cmd);
        sw.printIntermediate("Execution");
        try {
            return parseRunResult();
        } catch (IOException e) {
            throw new KRunExecutionException("Parsing maude output exception", e);
        }
    }

    private String setCounter() {
        return "red setCounter(" + Integer.toString(K.counter) + ") ." + K.lineSeparator;
    }

    private String getCounter() {
        return K.lineSeparator + "red counter .";
    }

    public KRunResult<KRunState> step(Term cfg, int steps) throws KRunExecutionException {
        String maude_cmd = K.maude_cmd;
        if (steps == 0) {
            K.maude_cmd = "red";
        } else {
            K.maude_cmd = "rew[" + Integer.toString(steps) + "]";
        }
        KRunResult<KRunState> result = run(cfg);
        K.maude_cmd = maude_cmd;
        return result;
    }

    //needed for --statistics command
    private String printStatistics(Element elem) {
        String result = "";
        if ("search".equals(K.maude_cmd)) {
            String totalStates = elem.getAttribute("total-states");
            String totalRewrites = elem.getAttribute("total-rewrites");
            String realTime = elem.getAttribute("real-time-ms");
            String cpuTime = elem.getAttribute("cpu-time-ms");
            String rewritesPerSecond = elem.getAttribute("rewrites-per-second");
            result += "states: " + totalStates + " rewrites: " + totalRewrites + " in " + cpuTime + "ms cpu (" + realTime + "ms real) (" + rewritesPerSecond + " rewrites/second)";
        } else if ("erewrite".equals(K.maude_cmd)){
            String totalRewrites = elem.getAttribute("total-rewrites");
            String realTime = elem.getAttribute("real-time-ms");
            String cpuTime = elem.getAttribute("cpu-time-ms");
            String rewritesPerSecond = elem.getAttribute("rewrites-per-second");
            result += "rewrites: " + totalRewrites + " in " + cpuTime + "ms cpu (" + realTime + "ms real) (" + rewritesPerSecond + " rewrites/second)";
        }
        return result;
    }



    private KRunResult<KRunState> parseRunResult() throws IOException {
        Document doc = XmlUtil.readXMLFromFile(K.maude_output);
        NodeList list;
        Node nod;
        list = doc.getElementsByTagName("result");
        nod = list.item(1);

        assertXML(nod != null && nod.getNodeType() == Node.ELEMENT_NODE);
        Element elem = (Element) nod;
        List<Element> child = XmlUtil.getChildElements(elem);
        assertXML(child.size() == 1);

        KRunState state = parseElement(child.get(0), context);
        KRunResult<KRunState> ret = new KRunResult<KRunState>(state);
        String statistics = printStatistics(elem);
        ret.setStatistics(statistics);
        ret.setRawOutput(FileUtil.getFileContent(K.maude_out));
        parseCounter(list.item(2));
        return ret;
    }

    private KRunState parseElement(Element el, org.kframework.kil.loader.Context context) {
        Term rawResult = MaudeKRun.parseXML(el, context);

        return new KRunState(rawResult, context);
    }

    private void parseCounter(Node counter) {
        assertXML(counter != null && counter.getNodeType() == Node.ELEMENT_NODE);
        Element elem = (Element) counter;
        List<Element> child = XmlUtil.getChildElements(elem);
        assertXML(child.size() == 1);
        IntBuiltin intBuiltin = (IntBuiltin) parseXML(child.get(0), context);
        K.counter = intBuiltin.bigIntegerValue().intValue() - 1;
    }

    private static void assertXML(boolean assertion) {
        if (!assertion) {
            GlobalSettings.kem.register(new KException(ExceptionType.ERROR, KExceptionGroup.CRITICAL, "Cannot parse result xml from maude. If you believe this to be in error, please file a bug and attach " + K.maude_output.replaceAll("/krun[0-9]*/", "/krun/")));
        }
    }
    
    private static class InvalidMaudeXMLException extends Exception {}

    private static void assertXMLTerm(boolean assertion) throws InvalidMaudeXMLException {
        if (!assertion) {
            throw new InvalidMaudeXMLException();
        }
    }

    public static Pattern emptyPattern = Pattern.compile("\\.(Map|Bag|List|Set|K)");

    public static Term parseXML(Element xml, Context context) {
        String op = xml.getAttribute("op");
        String sort = xml.getAttribute("sort");
        sort = sort.replaceAll("`([{}\\[\\](),])", "$1");
        List<Element> list = XmlUtil.getChildElements(xml);
        
        try {
            if ((sort.equals("BagItem") || sort.equals("[Bag]")) && op.equals("<_>_</_>")) {
                Cell cell = new Cell();
                assertXMLTerm(list.size() == 3 && list.get(0).getAttribute("sort").equals("CellLabel") && list.get(2).getAttribute("sort").equals("CellLabel") && list.get(0).getAttribute("op").equals(list.get(2).getAttribute("op")));

                cell.setLabel(list.get(0).getAttribute("op"));
                cell.setContents(parseXML(list.get(1), context));
                return cell;
            } else if ((sort.equals("BagItem") || sort.equals("[Bag]")) && op.equals("BagItem")) {
                assertXMLTerm(list.size() == 1);
                return new BagItem(parseXML(list.get(0), context));
            } else if ((sort.equals("MapItem") || sort.equals("[Map]")) && op.equals("_|->_")) {
                assertXMLTerm(list.size() == 2);
                return new MapItem(parseXML(list.get(0), context), parseXML(list.get(1), context));
            } else if ((sort.equals("SetItem") || sort.equals("[Set]")) && op.equals("SetItem")) {
                assertXMLTerm(list.size() == 1);
                return new SetItem(parseXML(list.get(0), context));
            } else if ((sort.equals("ListItem") || sort.equals("[List]")) && op.equals("ListItem")) {
                assertXMLTerm(list.size() == 1);
                return new ListItem(parseXML(list.get(0), context));
            } else if (op.equals("_`,`,_") && sort.equals("NeKList")) {
                assertXMLTerm(list.size() >= 2);
                List<Term> l = new ArrayList<Term>();
                for (Element elem : list) {
                    l.add(parseXML(elem, context));
                }
                return new KList(l);
            } else if (sort.equals("K") && op.equals("_~>_")) {
                assertXMLTerm(list.size() >= 2);
                List<Term> l = new ArrayList<Term>();
                for (Element elem : list) {
                    l.add(parseXML(elem, context));
                }
                return new KSequence(l);
            } else if (op.equals("__") && (sort.equals("NeList") || sort.equals("List") || sort.equals("[List]"))) {
                assertXMLTerm(list.size() >= 2);
                List<Term> l = new ArrayList<Term>();
                for (Element elem : list) {
                    l.add(parseXML(elem, context));
                }
                return new org.kframework.kil.List(l);
            } else if (op.equals("__") && (sort.equals("NeBag") || sort.equals("Bag") || sort.equals("[Bag]"))) {
                assertXMLTerm(list.size() >= 2);
                List<Term> l = new ArrayList<Term>();
                for (Element elem : list) {
                    l.add(parseXML(elem, context));
                }
                return new Bag(l);
            } else if (op.equals("__") && (sort.equals("NeSet") || sort.equals("Set") || sort.equals("[Set]"))) {
                assertXMLTerm(list.size() >= 2);
                List<Term> l = new ArrayList<Term>();
                for (Element elem : list) {
                    l.add(parseXML(elem, context));
                }
                return new org.kframework.kil.Set(l);
            } else if (op.equals("__") && (sort.equals("NeMap") || sort.equals("Map") || sort.equals("[Map]"))) {
                assertXMLTerm(list.size() >= 2);
                List<Term> l = new ArrayList<Term>();
                for (Element elem : list) {
                    l.add(parseXML(elem, context));
                }
                return new org.kframework.kil.Map(l);
            } else if ((op.equals("#_") || op.equals("List2KLabel_") || op.equals("Map2KLabel_") || op.equals("Set2KLabel_") || op.equals("Bag2KLabel_") || op.equals("KList2KLabel_") || op.equals("KLabel2KLabel_")) && (sort.equals(KSorts.KLABEL) || sort.equals("[KLabel]"))) {
                assertXMLTerm(list.size() == 1);
                Term term = parseXML(list.get(0), context);
                if (op.equals("#_") && term instanceof Token) {
                    return term;
                } else {
                    return new KInjectedLabel(term);
                }
            } else if (sort.equals("#NzInt") && op.equals("--Int_")) {
                assertXMLTerm(list.size() == 1);
                return IntBuiltin.of("-" + ((IntBuiltin) parseXML(list.get(0), context)).value());
            } else if (sort.equals("#NzNat") && op.equals("sNat_")) {
                assertXMLTerm(list.size() == 1 && parseXML(list.get(0), context).equals(IntBuiltin.ZERO_TOKEN));
                return IntBuiltin.of(xml.getAttribute("number"));
            } else if (sort.equals("#Zero") && op.equals("0")) {
                assertXMLTerm(list.size() == 0);
                return IntBuiltin.ZERO_TOKEN;
            } else if (sort.equals("#Bool") && (op.equals("true") || op.equals("false"))) {
                assertXMLTerm(list.size() == 0);
                return BoolBuiltin.of(op);
            } else if (sort.equals("#Char") || sort.equals("#String")) {
                assertXMLTerm(list.size() == 0);
                assertXMLTerm(op.startsWith("\"") && op.endsWith("\""));
                return StringBuiltin.of(StringUtil.unescape(op.substring(1, op.length() - 1)));
            } else if (op.equals("#token") && sort.equals(KSorts.KLABEL)) {
                // #token(String, String)
                assertXMLTerm(list.size() == 2);
                StringBuiltin sortString = (StringBuiltin) parseXML(list.get(0), context);
                StringBuiltin valueString = (StringBuiltin) parseXML(list.get(1), context);
                return GenericToken.of(sortString.stringValue(), valueString.stringValue());
            } else if (sort.equals("#FiniteFloat")) {
                assertXMLTerm(list.size() == 0);
                return GenericToken.of("#Float", op);
            } else if (emptyPattern.matcher(op).matches() && (sort.equals("Bag") || sort.equals("List") || sort.equals("Map") || sort.equals("Set") || sort.equals("K"))) {
                assertXMLTerm(list.size() == 0);
                if (sort.equals("Bag")) {
                    return Bag.EMPTY;
                } else if (sort.equals("List")) {
                    return org.kframework.kil.List.EMPTY;
                } else if (sort.equals("Map")) {
                    return org.kframework.kil.Map.EMPTY;
                } else if (sort.equals("Set")) {
                    return org.kframework.kil.Set.EMPTY;
                } else {
                    // sort.equals("K")
                    return KSequence.EMPTY;
                }
            } else if (op.equals(".KList") && sort.equals(KSorts.KLIST)) {
                assertXMLTerm(list.size() == 0);
                return KList.EMPTY;
            } else if (op.equals("_`(_`)") && (sort.equals(KSorts.KITEM) || sort.equals("[KList]"))) {
                assertXMLTerm(list.size() == 2);
                Term child = parseXML(list.get(1), context);
                if (!(child instanceof KList)) {
                    List<Term> terms = new ArrayList<Term>();
                    terms.add(child);
                    child = new KList(terms);
                }
                return new KApp(parseXML(list.get(0), context),child);
            } else if (sort.equals(KSorts.KLABEL) && list.size() == 0) {
                return KLabelConstant.of(StringUtil.unescapeMaude(op), context);
            } else if (sort.equals(KSorts.KLABEL) && op.equals("#freezer_")) {
                assertXMLTerm(list.size() == 1);
                return new FreezerLabel(parseXML(list.get(0), context));
            } else if (op.equals("HOLE")) {
                assertXMLTerm(list.size() == 0 && sort.equals(KSorts.KITEM));
                //return new Hole(sort);
                return Hole.KITEM_HOLE;
            } else {
                Set<String> conses = context.labels.get(StringUtil.unescapeMaude(op));
                Set<String> validConses = new HashSet<String>();
                List<Term> possibleTerms = new ArrayList<Term>();
                assertXMLTerm(conses != null);
                for (String cons : conses) {
                    Production p = context.conses.get(cons);
                    if (p.getSort().equals(sort) && p.getArity() == list.size()) {
                        validConses.add(cons);
                    }
                }
                assertXMLTerm(validConses.size() > 0);
                List<Term> contents = new ArrayList<Term>();
                for (Element elem : list) {
                    contents.add(parseXML(elem, context));
                }
                for (String cons : validConses) {
                    possibleTerms.add(new TermCons(sort, cons, contents, context));
                }
                if (possibleTerms.size() == 1) {
                    return possibleTerms.get(0);
                } else {
                    return new Ambiguity(sort, possibleTerms);
                }
            }
        } catch (InvalidMaudeXMLException e) {
            return new BackendTerm(sort, flattenXML(xml));
        }
    }

    public static String flattenXML(Element xml) {
        List<Element> children = XmlUtil.getChildElements(xml);
        if (children.size() == 0) {
            return xml.getAttribute("op");
        } else {
            String result = xml.getAttribute("op");
            if (!xml.getAttribute("number").equals("")) {
                result += "^" + xml.getAttribute("number");
            }
            String conn = "(";
            for (Element child : children) {
                result += conn;
                conn = ",";
                result += flattenXML(child);
            }
            result += ")";
            return result;
        }
    }

    private static String getSearchType(SearchType s) {
        if (s == SearchType.ONE) return "1";
        if (s == SearchType.PLUS) return "+";
        if (s == SearchType.STAR) return "*";
        if (s == SearchType.FINAL) return "!";
        return null;
    }

    public KRunResult<SearchResults> search(Integer bound, Integer depth,
                                        SearchType searchType, Rule pattern,
                                        Term cfg,
                                        RuleCompilerSteps compilationInfo)
            throws KRunExecutionException {
    StringBuilder cmd = new StringBuilder();
    if (K.trace) {
      cmd.append("set trace on .").append(K.lineSeparator);
    }
        cmd.append("set show command off .").append(K.lineSeparator).append(setCounter()).append("search ");
        if (bound != null && depth != null) {
            cmd.append("[").append(bound).append(",").append(depth).append("] ");
        } else if (bound != null) {
            cmd.append("[").append(bound).append("] ");
        } else if (depth != null) {
            cmd.append("[,").append(depth).append("] ");
        }
        MaudeFilter maudeFilter = new MaudeFilter(context);
        maudeFilter.visitNode(cfg);
        cmd.append(maudeFilter.getResult()).append(" ");
        MaudeFilter patternBody = new MaudeFilter(context);
        patternBody.visitNode(pattern.getBody());
        String patternString = "=>" + getSearchType(searchType) + " " + patternBody.getResult();
        //TODO: consider replacing Requires with Ensures here.
        if (pattern.getRequires() != null) {
            MaudeFilter patternCondition = new MaudeFilter(context);
            patternCondition.visitNode(pattern.getRequires());
            patternString += " such that " + patternCondition.getResult() + " = # true(.KList)";
        }
        cmd.append(patternString).append(" .");
    if (K.showSearchGraph || K.debug || K.guidebug) {
      cmd.append(K.lineSeparator).append("show search graph .");
    }
        cmd.append(getCounter());
    executeKRun(cmd);
    try {
            SearchResults results;
            final List<SearchResult> solutions = parseSearchResults
                    (pattern, compilationInfo);
            final boolean matches = patternString.trim().matches("=>[!*1+] " +
                    "<_>_</_>\\(generatedTop, B:Bag, generatedTop\\)");
      DirectedGraph<KRunState, Transition> graph
          = (K.showSearchGraph || K.debug || K.guidebug) ? parseSearchGraph() : null;
      results = new SearchResults(solutions, graph, matches, context);
            K.stateCounter += graph != null ? graph.getVertexCount() : 0;
            KRunResult<SearchResults> result = new KRunResult<SearchResults>(results);
      result.setRawOutput(FileUtil.getFileContent(K.maude_out));
            return result;
        } catch (IOException e) {
            if (context.globalOptions.debug) {
                e.printStackTrace();
            }
            GlobalSettings.kem.register(new KException(ExceptionType.ERROR, KExceptionGroup.INTERNAL, 
                    "IO error detected reading maude output"));
            throw new AssertionError("unreachable");
        }
    }

    private DirectedGraph<KRunState, Transition> parseSearchGraph() throws IOException {
        try (
            Scanner scanner = new Scanner(new File(K.maude_output));
            Writer writer = new OutputStreamWriter(new BufferedOutputStream(
                new FileOutputStream(K.processed_maude_output)))) {

            scanner.useDelimiter("\n");
            while (scanner.hasNext()) {
                String text = scanner.next();
                text = text.replaceAll("<data key=\"((rule)|(term))\">", "<data key=\"$1\"><![CDATA[");
                text = text.replaceAll("</data>", "]]></data>");
                writer.write(text, 0, text.length());
            }
        }

        Document doc = XmlUtil.readXMLFromFile(K.processed_maude_output);
        NodeList list;
        Node nod;
        list = doc.getElementsByTagName("graphml");
        assertXML(list.getLength() == 1);
        nod = list.item(0);
        assertXML(nod != null && nod.getNodeType() == Node.ELEMENT_NODE);
        XmlUtil.serializeXML(nod, K.processed_maude_output);
            
        Transformer<GraphMetadata, DirectedGraph<KRunState, Transition>> graphTransformer = new Transformer<GraphMetadata, DirectedGraph<KRunState, Transition>>() { 
            public DirectedGraph<KRunState, Transition> transform(GraphMetadata g) { 
                return new DirectedSparseGraph<KRunState, Transition>();
            }
        };
        Transformer<NodeMetadata, KRunState> nodeTransformer = new Transformer<NodeMetadata, KRunState>() {
            public KRunState transform(NodeMetadata n) {
                String nodeXmlString = n.getProperty("term");
                Element xmlTerm = XmlUtil.readXMLFromString(nodeXmlString).getDocumentElement();
                KRunState ret = parseElement(xmlTerm, context);
                String id = n.getId();
                id = id.substring(1);
                ret.setStateId(Integer.parseInt(id) + K.stateCounter);
                return ret;
            }
        };
        Transformer<EdgeMetadata, Transition> edgeTransformer = new Transformer<EdgeMetadata, Transition>() {
            public Transition transform(EdgeMetadata e) {
                String edgeXmlString = e.getProperty("rule");
                Element elem = XmlUtil.readXMLFromString(edgeXmlString).getDocumentElement();
                String metadataAttribute = elem.getAttribute("metadata");
                Pattern pattern = Pattern.compile("([a-z]*)=\\((.*?)\\)");
                Matcher matcher = pattern.matcher(metadataAttribute);
                String location = null;
                String filename = null;
                while (matcher.find()) {
                    String name = matcher.group(1);
                    if (name.equals("location"))
                        location = matcher.group(2);
                    if (name.equals("filename"))
                        filename = matcher.group(2);
                }
                if (location == null || location.equals("generated") || filename == null) {
                    // we should avoid this wherever possible, but as a quick fix for the
                    // superheating problem and to avoid blowing things up by accident when
                    // location information is missing, I am creating non-RULE edges.
                    String labelAttribute = elem.getAttribute("label");
                    if (labelAttribute == null) {
                        return Transition.unlabelled(context);
                    } else {
                        return Transition.label(labelAttribute, context);
                    }
                }
                return Transition.rule(context.locations.get(filename + ":(" + location + ")"),
                        context);
            }
        };

        Transformer<HyperEdgeMetadata, Transition> hyperEdgeTransformer = new Transformer<HyperEdgeMetadata, Transition>() {
            public Transition transform(HyperEdgeMetadata h) {
                throw new RuntimeException("Found a hyper-edge. Has someone been tampering with our intermediate files?");
            }
        };

        try (Reader processedMaudeOutputReader
                 = new BufferedReader(new FileReader(K.processed_maude_output))) {
            GraphMLReader2<DirectedGraph<KRunState, Transition>, KRunState, Transition> graphmlParser
                = new GraphMLReader2<>(processedMaudeOutputReader, graphTransformer, nodeTransformer,
                edgeTransformer, hyperEdgeTransformer);
            try {
                return graphmlParser.readGraph();
            } catch (GraphIOException e) {
                throw new IOException(e);
            }
        }
    }

    private List<SearchResult> parseSearchResults(Rule pattern, RuleCompilerSteps compilationInfo) {
        List<SearchResult> results = new ArrayList<SearchResult>();
        Document doc = XmlUtil.readXMLFromFile(K.maude_output);
        NodeList list;
        Node nod;
        list = doc.getElementsByTagName("search-result");
        for (int i = 0; i < list.getLength(); i++) {
            nod = list.item(i);
            assertXML(nod != null && nod.getNodeType() == Node.ELEMENT_NODE);
            Element elem = (Element) nod;
            if (elem.getAttribute("solution-number").equals("NONE")) {
                continue;
            }
            int stateNum = Integer.parseInt(elem.getAttribute("state-number"));
            Map<String, Term> rawSubstitution = new HashMap<String, Term>();
            NodeList assignments = elem.getElementsByTagName("assignment");
            for (int j = 0; j < assignments.getLength(); j++) {
                nod = assignments.item(j);
                assertXML(nod != null && nod.getNodeType() == Node.ELEMENT_NODE);
                elem = (Element) nod;
                List<Element> child = XmlUtil.getChildElements(elem);
                assertXML(child.size() == 2);
                Term result = parseXML(child.get(1), context);
                rawSubstitution.put(child.get(0).getAttribute("op"), result);
            }

            Term rawResult = (Term) new SubstitutionFilter(rawSubstitution, context)
                    .visitNode(pattern.getBody());
            KRunState state = new KRunState(rawResult, context);
            state.setStateId(stateNum + K.stateCounter);
            SearchResult result = new SearchResult(state, rawSubstitution, compilationInfo,
                    context);
            results.add(result);
        }
        list = doc.getElementsByTagName("result");
        nod = list.item(1);
        parseCounter(nod);
        return results;        
    }

    public KRunProofResult<DirectedGraph<KRunState, Transition>> modelCheck(Term formula, Term cfg) throws KRunExecutionException {
        MaudeFilter formulaFilter = new MaudeFilter(context);
        formulaFilter.visitNode(formula);
        MaudeFilter cfgFilter = new MaudeFilter(context);
        cfgFilter.visitNode(cfg);

        StringBuilder cmd = new StringBuilder()
            .append("mod MCK is").append(K.lineSeparator)
            .append(" including ")
            .append(K.main_module).append(" .").append(K.lineSeparator).append(K.lineSeparator)
            .append(" op #initConfig : -> Bag .").append(K.lineSeparator).append(K.lineSeparator)
            .append(" eq #initConfig  =").append(K.lineSeparator)
            .append(cfgFilter.getResult()).append(" .").append(K.lineSeparator)
            .append("endm").append(K.lineSeparator).append(K.lineSeparator)
            .append("red").append(K.lineSeparator)
            .append("_`(_`)(('modelCheck`(_`,_`)).KLabel,_`,`,_(_`(_`)(Bag2KLabel(#initConfig),.KList),").append(K.lineSeparator)
            .append(formulaFilter.getResult()).append(")").append(K.lineSeparator)
            .append(") .");
        boolean io = ioServer;
        ioServer = false;
        executeKRun(cmd);
        ioServer = io;
        KRunProofResult<DirectedGraph<KRunState, Transition>> result = parseModelCheckResult();
        result.setRawOutput(FileUtil.getFileContent(K.maude_out));
        return result;
    }

    public KRunResult<TestGenResults> generate(Integer bound, Integer depth, SearchType searchType, Rule pattern, Term cfg, RuleCompilerSteps compilationInfo) throws KRunExecutionException{
        throw new UnsupportedOperationException("--generate-tests");
    }

    private KRunProofResult<DirectedGraph<KRunState, Transition>> parseModelCheckResult() {
        Document doc = XmlUtil.readXMLFromFile(K.maude_output);
        NodeList list;
        Node nod;
        list = doc.getElementsByTagName("result");
        assertXML(list.getLength() == 1);
        nod = list.item(0);
        assertXML(nod != null && nod.getNodeType() == Node.ELEMENT_NODE);
        Element elem = (Element) nod;
        List<Element> child = XmlUtil.getChildElements(elem);
        assertXML(child.size() == 1);
        String sort = child.get(0).getAttribute("sort");
        String op = child.get(0).getAttribute("op");
        assertXML(op.equals("_`(_`)") && sort.equals(KSorts.KITEM));
        child = XmlUtil.getChildElements(child.get(0));
        assertXML(child.size() == 2);
        sort = child.get(0).getAttribute("sort");
        op = child.get(0).getAttribute("op");
        assertXML(op.equals("#_") && sort.equals(KSorts.KLABEL));
        sort = child.get(1).getAttribute("sort");
        op = child.get(1).getAttribute("op");
        assertXML(op.equals(".KList") && sort.equals(KSorts.KLIST));
        child = XmlUtil.getChildElements(child.get(0));
        assertXML(child.size() == 1);
        elem = child.get(0);
        if (elem.getAttribute("op").equals("true") && elem.getAttribute("sort").equals("#Bool")) {
            return new KRunProofResult<DirectedGraph<KRunState, Transition>>(true, null);
        } else {
            sort = elem.getAttribute("sort");
            op = elem.getAttribute("op");
            assertXML(op.equals("LTLcounterexample") && sort.equals("#ModelCheckResult"));
            child = XmlUtil.getChildElements(elem);
            assertXML(child.size() == 2);
            List<MaudeTransition> initialPath = new ArrayList<MaudeTransition>();
            List<MaudeTransition> loop = new ArrayList<MaudeTransition>();
            parseCounterexample(child.get(0), initialPath, context);
            parseCounterexample(child.get(1), loop, context);
            DirectedGraph<KRunState, Transition> graph = new DirectedOrderedSparseMultigraph<KRunState, Transition>();
            Transition edge = null;
            KRunState vertex = null;
            for (MaudeTransition trans : initialPath) {
                graph.addVertex(trans.state);
                if (edge != null) {
                    graph.addEdge(edge, vertex, trans.state);
                }
                edge = trans.label;
                vertex = trans.state;
            }
            for (MaudeTransition trans : loop) {
                graph.addVertex(trans.state);
                if (edge != null) {
                    graph.addEdge(edge, vertex, trans.state);
                }
                edge = trans.label;
                vertex = trans.state;
            }
            graph.addEdge(edge, vertex, loop.get(0).state);
            
            return new KRunProofResult<DirectedGraph<KRunState, Transition>>(false, graph);
        }
    }

    private static class MaudeTransition {
        public KRunState state;
        public Transition label;

        public MaudeTransition(KRunState state, Transition label) {
            this.state = state;
            this.label = label;
        }
    }

    private static void parseCounterexample(Element elem, List<MaudeTransition> list, Context context) {
        String sort = elem.getAttribute("sort");
        String op = elem.getAttribute("op");
        List<Element> child = XmlUtil.getChildElements(elem);
        if (sort.equals("#TransitionList") && op.equals("_LTL_")) {
            assertXML(child.size() >= 2);
            for (Element e : child) {
                parseCounterexample(e, list, context);
            }
        } else if (sort.equals("#Transition") && op.equals("LTL`{_`,_`}")) {
            assertXML(child.size() == 2);
            Term t = parseXML(child.get(0), context);
        
            List<Element> child2 = XmlUtil.getChildElements(child.get(1));
            sort = child.get(1).getAttribute("sort");
            op = child.get(1).getAttribute("op");
            //#Sort means we included the meta level and so it thinks Qids
            //aren' Qids even though they really are.
            assertXML(child2.size() == 0 && (sort.equals("#Qid") || sort.equals("#RuleName") || sort.equals("#Sort")));
            String label = op;
            Transition trans;
            if (sort.equals("#RuleName") && op.equals("UnlabeledLtl")) {
                trans = Transition.unlabelled(context);
            } else if (sort.equals("#RuleName") && op.equals("deadlockLtl")) {
                trans = Transition.deadlock(context);
            } else {
                trans = Transition.label(label, context);
            }
            list.add(new MaudeTransition(new KRunState(t, context), trans));
        } else if (sort.equals("#TransitionList") && op.equals("LTLnil")) {
            assertXML(child.size() == 0);
        } else {
            GlobalSettings.kem.register(new KException(ExceptionType.ERROR, KExceptionGroup.CRITICAL, "Cannot parse result xml from maude due to production " + op + " of sort " + sort + ". Please file an error on the issue tracker which includes this error message."));
            assertXML(false);
        }
    }

    public KRunDebugger debug(Term cfg) throws KRunExecutionException {
        return new KRunApiDebugger(this, cfg, context);
    }

    public KRunDebugger debug(DirectedGraph<KRunState, Transition> graph) {
        return new KRunApiDebugger(this, graph);
    }

    public KRunProofResult<Set<Term>> prove(Module m, Term KAST) {
        throw new UnsupportedBackendOptionException("--prove");
    }
}
