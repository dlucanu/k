// Copyright (c) 2012-2014 K Team. All Rights Reserved.
package org.kframework.kil.loader;

import java.util.Set;

import org.kframework.kil.KLabelConstant;
import org.kframework.kil.PriorityBlock;
import org.kframework.kil.PriorityExtendedAssoc;
import org.kframework.kil.Production;
import org.kframework.kil.visitors.BasicVisitor;
import org.kframework.parser.generator.SDFHelper;

public class UpdateAssocVisitor extends BasicVisitor {
    public UpdateAssocVisitor(Context context) {
        super(context);
    }

    /**
     * Because the block associativity is not reflexive in SDF, I have to add it manually.
     */
    @Override
    public Void visit(PriorityExtendedAssoc pri, Void _) {
        for (KLabelConstant c : pri.getTags()) {
            Set<Production> prods = SDFHelper.getProductionsForTag(c.getLabel(), context);
            for (Production p : prods) {
                context.putAssoc(p.getCons(), prods);
                if (!p.getAttributes().containsKey("left") && !p.getAttributes().containsKey("right") && !p.getAttributes().containsKey("non-assoc")) {
                    p.addAttribute(pri.getAssoc(), "");
                }
            }
        }
        return null;
    }

    @Override
    public Void visit(PriorityBlock pri, Void _) {
        if (!pri.getAssoc().equals("")) {
            for (Production p : pri.getProductions()) {
                if (!p.getAttributes().containsKey("left") && !p.getAttributes().containsKey("right") && !p.getAttributes().containsKey("non-assoc")) {
                    p.addAttribute(pri.getAssoc(), "");
                    context.putAssoc(p.getCons(), pri.getProductions());
                }
            }
        }
        return null;
    }
}
