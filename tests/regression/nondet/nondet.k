// Copyright (c) 2013-2014 K Team. All Rights Reserved.
module NONDET
     syntax K ::= K "," K [strict, left, superheat]
                | puts(String) | write(String)
     syntax KResult ::= "done"  
     
     configuration
          <k> puts("a") , puts("b") , puts("c") </k>
          <out> "" </out>
          <cc> .K </cc> 
 
     rule done , done => done                                                   

     rule <k> write(S:String) => .K ...</k>
          <out> Buf:String => Buf +String S </out>
                                                                                
     rule <k> puts(S:String) ~> K:K => write(S) ~> done </k>
          <cc> _ => K </cc>
                                                                                
     rule <k> done ~> (.K => C) </k>
          <cc> C:K => .K </cc>
          when C =/=K .K [supercool]
                                                                                
     rule (<k> done </k> => .Bag)
          (<cc> .K </cc> => .Bag)
endmodule

