// Copyright (c) 2013-2014 K Team. All Rights Reserved.
/*@ ensures \result == dmax(a,b); */
/*
double max(double a, double b){
    return (a<=b)?b:a;
}
*/
module MAX-RULE
  rule <threads>
           <thread>...
	     <k> max(Vx:Float, Vy:Float) => V:Float </k>
             <env> GENV </env>
	   ...</thread>
       </threads>
       <store>... STORE ...</store>
       <genv> GENV </genv>
     ensures V ==Float dmax(Vx,Vy)
endmodule
