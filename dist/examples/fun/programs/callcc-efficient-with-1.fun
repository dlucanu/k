// using callcc for exceptions
// the following is efficient

let f l =
  callcc (fun throw ->
           rec aux l =
             if null? l
             then 1
             else if head l == 0
                  then throw 0
                  else head l * aux (tail l)
           in aux l)
in f [1,2,3,4,5,6,7,8,9,8,7,6,5,4,3,2,1,0,1,2,3,4,5,6,7,8,9]