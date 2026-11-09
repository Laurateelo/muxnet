const fs=require('node:fs');
const path=require('node:path');
const {DefaultArtifactClient}=require('@actions/artifact');
(async()=>{
 const [name,root]=process.argv.slice(2);
 const files=fs.readdirSync(root,{withFileTypes:true}).filter(x=>x.isFile()).map(x=>path.join(root,x.name));
 const client=new DefaultArtifactClient();
 const prefix=`lab3-live-${process.env.GITHUB_RUN_ID}-`;
 const previous=(await client.listArtifacts()).artifacts.filter(a=>a.name.startsWith(prefix)).sort((a,b)=>b.id-a.id);
 // Keep one previous transport copy. Every original frame stays on disk and
 // is included in the final evidence artifact, so no evidence is discarded.
 for(const old of previous.slice(1)) await client.deleteArtifact(old.name);
 const result=await client.uploadArtifact(name,files,root,{retentionDays:14});
 console.log(JSON.stringify({artifactName:name,...result}));
})().catch(e=>{console.error(e);process.exit(1)});
