// Run with: node --test app/src/test/claim-campaign-script.test.cjs
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const source = fs.readFileSync(path.join(__dirname, '../main/java/ai/zcode/remote/ui/remote/event/ClaimCampaignScript.kt'), 'utf8')
  .match(/val js = """([\s\S]*?)"""/)[1];

const delivery = {scope:'fixture-account', serverTime:100, deliveries:[{campaign_id:'fixture-campaign', banner:{
  background:{args:{zcode_plan:{name:'Fixture offer', entitlements:[{show_name:'Test model',grant_units:100000000,unit_type:'token'}]}}},
  buttons:[{action:{type:'claim_zcode_plan',args:{plan_id:'fixture-plan'}}}]
}}]};

function harness({previewOnly=false, success=true, interactive=false}={}) {
  const state={queries:0,claims:0,reports:[],deliveries:[],clicks:[],now:1000000};
  const nodes=new Map();
  function element(tag='div') {
    const e={tagName:tag.toUpperCase(),children:[],dataset:{},style:{},hidden:false,textContent:'',
      setAttribute(){},appendChild(child){this.children.push(child);if(child.id)nodes.set(child.id,child);},
      remove(){if(this.id)nodes.delete(this.id);},insertAdjacentElement(_,child){if(child.id)nodes.set(child.id,child);},
      click(){this.onclick?.({stopPropagation(){}});}};
    return e;
  }
  const header=element(), parent=element(), heading=element('h1');
  heading.textContent='当前设备上的工作区和任务';heading.parentElement=parent;parent.parentElement=header;
  const svc={
    marketingTouchService:{async query(){state.queries++;if(previewOnly)throw Error('unsupported');return delivery;},async report(x){state.reports.push(x);}},
    codingPlanSubscriptionService:{async getManualClaimPlanPreviews(){return {plans:[{planId:'fixture-plan',name:'Fixture offer'}]};},
      async getCaptchaConfig(){return {enabled:true,sceneId:'fixture-scene'};},
      async claimManualPlan(args){state.claims++;assert.equal(args.captchaVerifyParam,'fixture-verification');return {success,code:3007};}}
  };
  const root={memoizedState:{memoizedState:svc}}, fiber={__reactFiberFixture:root};
  const context={console,Set,Promise,Date:{now:()=>state.now},
    setTimeout(){return 1;},clearTimeout(){},setInterval(){},MutationObserver:class{observe(){}},
    document:{createElement:element,body:element(),head:element(),
      getElementById:id=>nodes.get(id),querySelectorAll:selector=>{
        if(selector==='*')return [fiber];if(selector==='h1')return [heading];
        if(selector==='#zcode-claim-offers button')return (nodes.get('zcode-claim-offers')?.children||[]).flatMap(c=>c.children).filter(c=>c.tagName==='BUTTON');
        return [];
      }}};
  context.window={addEventListener(){},__zcodeNative:{onClaimCampaigns:body=>state.deliveries.push(JSON.parse(body)),onClaimCampaignClicked:key=>state.clicks.push(key)},
    initAliyunCaptcha:cfg=>{
      nodes.get('zcode-claim-captcha-trigger').onclick=()=>cfg.success('fixture-verification');
      cfg.getInstance({destroy(){},startTracelessVerification(){
        if(interactive)cfg.fail();else cfg.success('fixture-verification');
      }});
    }};
  vm.runInNewContext(source,context);
  return {state,context,nodes,buttons:()=>context.document.querySelectorAll('#zcode-claim-offers button'),
    settle:()=>new Promise(resolve=>setImmediate(resolve))};
}

test('discovery renders benefits without claiming, acknowledging or reporting',async()=>{
  const h=harness();await h.settle();
  assert.equal(h.state.claims,0);assert.deepEqual(h.state.clicks,[]);assert.deepEqual(h.state.reports,[]);
  assert.equal(h.state.deliveries.length,1);assert.equal(h.buttons().length,1);
  assert.equal(h.nodes.get('zcode-claim-offers').children[0].children[0].children[1].textContent,'Test model 100,000,000 tokens');
});
test('polling uses desktop cadence while later server deliveries still reach native',async()=>{
  const h=harness();await h.settle();await h.context.window.__zcodeClaims.refresh(false);
  assert.equal(h.state.queries,1);
  h.state.now+=600001;await h.context.window.__zcodeClaims.refresh(false);
  assert.equal(h.state.queries,2);assert.equal(h.state.deliveries.length,2);assert.equal(h.state.claims,0);
});
test('explicit fixture click verifies once and reports successful confirmation',async()=>{
  const h=harness();await h.settle();const button=h.buttons()[0];button.click();button.click();await h.settle();
  assert.equal(h.state.claims,1);assert.equal(h.state.clicks.length,1);
  assert.equal(h.state.reports[0].actionType,'confirm');
  assert.match(h.nodes.get('zcode-claim-status').textContent,/领取成功/);
});
test('failed fixture claim remains available and never reports success',async()=>{
  const h=harness({success:false});await h.settle();h.buttons()[0].click();await h.settle();
  assert.equal(h.state.claims,1);assert.equal(h.state.reports.length,0);assert.equal(h.buttons()[0].disabled,false);
  assert.match(h.nodes.get('zcode-claim-status').textContent,/验证码校验失败/);
});
test('interactive SDK fallback completes verification before submitting a fixture claim',async()=>{
  const h=harness({interactive:true});await h.settle();h.buttons()[0].click();await h.settle();
  assert.equal(h.state.claims,1);assert.match(h.nodes.get('zcode-claim-status').textContent,/领取成功/);
});
test('older desktop preview supplies an entry without inventing a server delivery',async()=>{
  const h=harness({previewOnly:true});await h.settle();assert.equal(h.buttons().length,1);
  assert.equal(h.state.deliveries.length,0);assert.equal(h.state.claims,0);
});
