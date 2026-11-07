const fs=require('node:fs');
const path=require('node:path');
const {DefaultArtifactClient}=require('@actions/artifact');
(async()=>{
 const [name,root]=process.argv.slice(2);
 const files=fs.readdirSync(root,{withFileTypes:true}).filter(x=>x.isFile()).map(x=>path.join(root,x.name));
 const result=await new DefaultArtifactClient().uploadArtifact(name,files,root,{retentionDays:14});
 console.log(JSON.stringify({artifactName:name,...result}));
})().catch(e=>{console.error(e);process.exit(1)});
