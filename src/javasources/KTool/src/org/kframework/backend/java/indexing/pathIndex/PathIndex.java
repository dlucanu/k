package org.kframework.backend.java.indexing.pathIndex;

import com.google.common.collect.Sets;
import org.apache.commons.collections15.MultiMap;
import org.apache.commons.collections15.multimap.MultiHashMap;
import org.kframework.backend.java.indexing.pathIndex.visitors.CoolingRuleVisitor;
import org.kframework.backend.java.indexing.pathIndex.visitors.HeatingRuleVisitor;
import org.kframework.backend.java.indexing.pathIndex.visitors.RuleVisitor;
import org.kframework.backend.java.kil.*;
import org.kframework.backend.java.util.LookupCell;
import org.kframework.kil.Production;

import java.util.*;
import java.util.Collection;

/**
 * Author: Owolabi Legunsen
 * 1/8/14: 10:08 AM
 */
//TODO(OwolabiL): How to deal with macros and function rules? (imp has none)
public class PathIndex {
    private Map<Integer, Rule> indexedRules;
    private Definition definition;
    private org.kframework.backend.java.indexing.pathIndex.trie.PathIndexTrie trie;
    private MultiMap<Integer,String> pStringMap;

    public enum RuleType{
        COOLING,
        HEATING,
        OTHER
    }

    public PathIndex(Definition definition) {
        this.definition = definition;
        this.indexedRules = new HashMap<>();
        constructIndex(definition);
    }

    //TODO(Owolabi): should this be called from constructor or from client?
    private void constructIndex(Definition definition) {
        pStringMap = new MultiHashMap<>();
        int count = 1;
        //Step 1: initialize the Trie

        //Step 2: extract a p-string PS(i) from the LHS of each rule(i)
        //Step 3: assign a numeric index to identify the rule IND(i)
        for (Rule rule : definition.rules()) {
            if (rule.containsAttribute("heat")) {
                pStringMap.putAll(createRulePString(rule, count, RuleType.HEATING));
            } else if (rule.containsAttribute("cool")) {
                pStringMap.putAll(createRulePString(rule, count, RuleType.COOLING));
            } else {
                pStringMap.putAll(createRulePString(rule, count, RuleType.OTHER));
            }
            indexedRules.put(count, rule);
            count++;
        }

        assert indexedRules.size() == definition.rules().size();
        printIndices(indexedRules, pStringMap);

        //intitialize the trie
        trie = new org.kframework.backend.java.indexing.pathIndex.trie.PathIndexTrie();

        //add all the pStrings to the trie
        ArrayList<String> strings;
        for (Integer key : pStringMap.keySet()) {
            strings = (ArrayList<String>) pStringMap.get(key);
            for (String string : strings) {
                trie.addIndex(trie.getRoot(), string.substring(2), key);
            }
        }
    }

    private void printIndices(Map<Integer, Rule> indexedRules, MultiMap<Integer,
            String> pString) {
        for (Integer n : indexedRules.keySet()) {
            System.out.println("Rule " + n + ": ");
            System.out.println(indexedRules.get(n));
            System.out.println("Rule Attribute:" );
            System.out.println(indexedRules.get(n).getAttributes());
            System.out.println("P-Strings: ");
            ArrayList<String> p_strings = (ArrayList<String>) pString.get(n);
            //TODO(Owolabi): there should be no null p-string!!
            if (p_strings != null) {
                for (int i = 0; i < p_strings.size(); i++) {
                    System.out.println((i + 1) + ": " + p_strings.get(i));
                }
            }
            System.out.println();
        }
    }


    private MultiMap<Integer,String> createRulePString(Rule rule, int n, RuleType type){
        RuleVisitor ruleVisitor;
        switch (type){
            case COOLING:
                ruleVisitor = new CoolingRuleVisitor(rule);
                break;
            case HEATING:
                ruleVisitor = new HeatingRuleVisitor(rule, definition.context());
                break;
            case OTHER:
                ruleVisitor = new RuleVisitor();
                break;
            default:
                throw new IllegalArgumentException("Cannot create P-String for unknown rule tye:"+type);
        }

        MultiMap<Integer, String> pStrings = new MultiHashMap<>();
        rule.accept(ruleVisitor);
        pStrings.putAll(n,ruleVisitor.getpStrings());
        return pStrings;
    }

    public Set<Rule> getRulesForTerm(Term term) {
        ArrayList<String> pStrings = getTermPString(term);
        Set<Rule> rules = new HashSet<>();
        //find the intersection of all the sets returned
        Set<Integer> matchingIndices = new HashSet<>();
        if (pStrings.size() > 1) {
            Set<Integer> nextRetrieved;
            Set<Integer> currentMatch = trie.retrieve(trie.getRoot(), pStrings.get(0));
            for (String pString : pStrings.subList(1, pStrings.size())) {
                nextRetrieved = trie.retrieve(trie.getRoot(), pString);
                if (nextRetrieved != null && currentMatch != null) {
                    currentMatch = Sets.intersection(currentMatch, nextRetrieved);
                }

                if (nextRetrieved != null && currentMatch == null) {
                    currentMatch = nextRetrieved;
                }

                //TODO(OwolabiL):Another terrible hack that should be removed!!! Needed with general sorts
                //This is a result of not yet knowing how to manipulate the sort hierarchy in
                // the index
//                if (nextRetrieved == null && currentMatch != null){
//                    ArrayList<String> list = new ArrayList<>();
//                    list.add(pString);
//                    currentMatch = Sets.union(currentMatch,getClosestIndices(list));
//                }
            }
            if (currentMatch != null) {
                matchingIndices.addAll(currentMatch);
            }

        } else if (pStrings.size() == 1) {
            matchingIndices.addAll(trie.retrieve(trie.getRoot(), pStrings.get(0)));
        }
        //TODO(OwolabiL): Bad hack to be removed. Manipulate sorts instead
        //this is needed if we had multiple pStrings that do not match any rules
        //e.g. for imp, [@.'_+_.1.Id] should match [@.'_+_.1.KItem] or [@.'_+_.1.AExp] but it
        // currently doesn't
        if (matchingIndices.size() == 0 && pStrings.size() != 0){
            Set<Integer> closestIndices = getClosestIndices(pStrings);
            matchingIndices.addAll(closestIndices);
        }

        for (Integer n : matchingIndices) {
            rules.add(indexedRules.get(n));
        }

        return rules;
    }

    private Set<Integer> getClosestIndices(ArrayList<String> pStrings) {
        Set<Integer> candidates = new HashSet<>();
        String firstPString = pStrings.get(0);
        String sub = firstPString.substring(0,firstPString.lastIndexOf("."));
        for (Map.Entry<Integer,Collection<String>> entry: pStringMap.entrySet()){
            for (String str : entry.getValue()){
                if (str.startsWith(sub)){
                    candidates.add(entry.getKey());
                }
            }
        }
        return candidates;
    }

    private ArrayList<String> getTermPString(Term term) {
        //TODO(OwolabiL): another case for generality using visitors: should be able to use the same pString generator as for rules!
        Cell kCell = LookupCell.find(term, "k");
        ArrayList<String> candidates = new ArrayList<>();
        Term kTerm = kCell.getContent();
        if (kTerm instanceof KSequence) {
            if (((KSequence) kTerm).size() > 0) {
                // Is this so in general? If head is not a token, treat as a normal KItem
                //Here, an instance of Token at the head means that a cooling rule should apply.
                Term sequenceHead = ((KSequence) kTerm).get(0);
                if (sequenceHead instanceof Token) {
                    String string1;
                    if (definition.context().isSubsorted("KResult", ((Token) sequenceHead).sort())) {
                        string1 = "@.KResult.";
                        Term sequenceSecond = ((KSequence) kTerm).get(1);
                        if (sequenceSecond instanceof KItem) {
                            KItem kItem = (KItem) sequenceSecond;
                            KLabel kLabel = kItem.kLabel();
                            if (kLabel instanceof KLabelFreezer) {
                                //TODO(OwolabiL): This is duplicated code!!!!!!!
                                KLabelFreezer freezer = (KLabelFreezer) kLabel;
                                KItem frozenItem = (KItem) freezer.term();
                                String frozenItemLabel = frozenItem.kLabel().toString();
                                Term frozenItemListMember1 = frozenItem.kList().get(0);
                                Term frozenItemListMember2;
                                String frozenItem2String;

                                String frozenItem1String;
                                if (frozenItemListMember1 instanceof Hole) {
                                    frozenItem1String = "HOLE";
                                } else {
                                    //is it always a variable? No! Here it can be an UninterpretedToken
                                    frozenItem1String = ((Token) frozenItemListMember1).sort();
                                }

                                if(frozenItem.kList().size() > 1){
                                    frozenItemListMember2 = frozenItem.kList().get(1);
                                    if (frozenItemListMember2 instanceof Hole) {
                                        frozenItem2String = "HOLE";
                                        String string3 = string1 + "1." + frozenItemLabel + ".2." + frozenItem2String;
                                        candidates.add(string3);
                                    } else {
                                        if (frozenItemListMember2 instanceof KItem){
                                            frozenItem2String = ((KItem) frozenItemListMember2).sort();
                                            String string3 = string1 + "1." + frozenItemLabel + ".2." + frozenItem2String;
                                            candidates.add(string3);
                                        } else if (frozenItemListMember2 instanceof Token){
                                            frozenItem2String = ((Token) frozenItemListMember2).sort();
                                            String string3 = string1 + "1." + frozenItemLabel + ".2." + frozenItem2String;
                                            candidates.add(string3);
                                        }
                                    }
                                }

                                String string2 = string1 + "1." + frozenItemLabel + ".1." + frozenItem1String;
                                // end of duplicated code
                                candidates.add(string2);
                            }
                        }
                    } else {
                        string1 = "@." + ((Token) sequenceHead).sort();
                        candidates.add(string1);
                    }
                } else if (sequenceHead instanceof KItem) {
                    //TODO(OwolabiL): More duplicated code. Remove!!!!
                    KItem kItem = (KItem) sequenceHead;
                    KLabel kLabel = kItem.kLabel();
                    String string1 = "@." + kLabel.toString();
                    KList kList = kItem.kList();

                    if (kList.size()==0){
                        String string = "@."+kLabel.toString()+".1."+ "#ListOf#Bot{\",\"}";
                        candidates.add(string);
                    }

                    Term kListTerm;
                    String pString = null;
                    for (int i = 0; i < kList.size(); i++) {
                        kListTerm = kList.get(i);
                        //for imp there are two cases for the first element:
                        //(1) it is a kItem
                        if (kListTerm instanceof KItem) {
                            KItem innerKItem = (KItem) kListTerm;
                            pString = string1 + "."+(i+1)+"." + innerKItem.sort();
                            candidates.add(pString);

                        } else if (kListTerm instanceof Token) {
                            //(2) it is an uninterpretedToken
                            String sort = ((Token) kListTerm).sort();
                            //if it is a KResult, use as is
                            if (definition.context().isSubsorted("KResult", sort)){
                                pString = string1 + "."+(i+1)+"." + sort;
                            } else {
                                ArrayList<Production> productions = (ArrayList<Production>) definition.context().productionsOf(kLabel.toString());
                                Production p = productions.get(0);
                                pString = string1 + "."+(i+1)+"." + p.getChildSort(0);
                            }
                            //else use sort from the production in this position
                            candidates.add(pString);
                        }


                    }
                }
            }

        } else {

            KItem kItem = (KItem) kTerm;
            KLabel kLabel = kItem.kLabel();
            String string1 = "@." + kLabel.toString();
            KList kList = kItem.kList();

            if (kList.size()==0){
                String string = "@."+kLabel.toString()+".1."+ "#ListOf#Bot{\",\"}";
                candidates.add(string);
            }

            Term kListTerm;
            String pString;

            for (int i = 0; i < kList.size(); i++) {
                 kListTerm = kList.get(i);
                if (kListTerm instanceof KItem) {
                    KItem innerKItem = (KItem) kListTerm;
                    pString = string1 + "."+(i+1)+"." + innerKItem.sort();
                    candidates.add(pString);

                } else if (kListTerm instanceof Token) {
                    //(2) it is an uninterpretedToken
                    pString = string1 + "."+(i+1)+"." + ((Token) kListTerm).sort();
                    candidates.add(pString);
                }
            }

        }
        return candidates;
    }
}
