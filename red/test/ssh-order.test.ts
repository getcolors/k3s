import {test,expect} from "bun:test";
import {wireFn} from "../src/workflow.ts";
for(const event of ["create","build"])test(`SSH alias precedes remote convergence on ${event}`,()=>{
 const opts={"red/event":event};
 expect(wireFn("k3s/compute",opts)?.slice(1)).toEqual(["k3s/ansible-local"]);
 expect(wireFn("k3s/ansible-local",opts)?.slice(1)).toEqual(["k3s/ansible-remote"]);
});
