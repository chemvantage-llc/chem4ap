  var agreeTerms;
  var agreeNoRefunds
  var selectPaymentMethod;
  var payment_div;
  var sig;
  var client_id;
  var platform_deployment_id;
  var price;
  var nmonths;
  
  function showSelectPaymentMethod() {
    agreeTerms = document.getElementById('terms');
    agreeNoRefunds = document.getElementById('norefunds');
    selectPaymentMethod = document.getElementById('select_payment_method');
    if (agreeTerms.checked && agreeNoRefunds.checked) {
      agreeTerms.disabled = true;
      agreeNoRefunds.disabled = true;
      selectPaymentMethod.style = 'display:inline';
      client_id = document.getElementById('client_id').value;
      platform_deployment_id = document.getElementById('platform_deployment_id').value;
      price = document.getElementById('price').value;
      sig = document.getElementById('sig').value;
      payment_div = document.getElementById('payment_div');
    }
   	else selectPaymentMethod.style = 'display:none';
  }
    
  function startCheckout() {
    nmonths = document.getElementById('nmonths').value;
    let value = price * (nmonths - Math.floor(nmonths/3));
    selectPaymentMethod.innerHTML = "<h2>" + nmonths + "-month subscription: $" + value + ".00 USD" + "</h2>";
    payment_div.style = "display: inline";
  }
   
  window.paypal.Buttons({
      style: {
        shape: 'rect',
        color: 'gold',
        layout: 'vertical',
        label: 'paypal'
      },
      
      createOrder: function(data, actions) {
        return fetch("/checkout", {
          method: "post",
          headers: { "Content-Type": "application/x-www-form-urlencoded" },
          body: new URLSearchParams({
        	"UserRequest": "CreateOrder",
        	"sig": sig,
        	"d": platform_deployment_id,
        	"nmonths": nmonths
   	  	  })
        })
        .then((response) => {
          if (response.ok) {
            return response.text().then(text => {
              if (!text) {
                throw new Error('Empty response from server');
              }
              return JSON.parse(text);
            });
          } else {
            throw new Error('API call failed. Response status code was ' + response.status);
          }
        })
        .then((order) => {
          return order.id 
        })
        .catch(error => {
          console.error('Network or CORS error:', error);
        });
      },
      
      onApprove: function(data, actions) {
        let order_id = data.orderID;
        return fetch("/checkout", {
          method: "post",
          headers: { "Content-Type": "application/x-www-form-urlencoded" },
          body: new URLSearchParams({
            "UserRequest": "CompleteOrder",
            "sig": sig,
            "order_id": order_id
          })
        })
        .then((response) => {
          if (response.ok) {
            return response.text().then(text => {
              if (!text) {
                throw new Error('Empty response from server');
              }
              return JSON.parse(text);
            });
          } else {
            throw new Error('API call failed. Response status code was ' + response.status);
          }
        })
        .then((order_details) => {
          let assignmentType = document.getElementById('assignment_type').value;
          selectPaymentMethod.innerHTML = "<h2>Thank you for your purchase</h2>"
            + order_details.purchase_units[0].payments["captures"][0].amount.value + " "
            + order_details.purchase_units[0].payments["captures"][0].amount.currency_code + "<br/>"
            + "Your " + nmonths + "-month Chem4AP subscription is now active and expires on " + order_details.expires + ".<br/>"
            + "OrderId: " + order_id + "<br/>"
            + "Please print a copy of this page for your records.<br/><br/>"
            + "<a class='btn btn-primary' href='/" + assignmentType + "/index.html?t=" + order_details.token + "'>Proceed to Chem4AP</a><br/><br/>";
          payment_div.style = "display: none";
        })
        .catch((error) => {
      	  console.log(error);
      	  selectPaymentMethod.innerHTML = "Sorry, an error occurred. Your payment was not completed.";
      	  payment_div.style = "display: none";
        });       
      },
      
      onCancel: function(data) {
      	console.log(data);
      	selectPaymentMethod.innerHTML = "<h2>Order canceled.</h2>"
      	  + "To continue, please launch the assignment again in your LMS.";
      	payment_div.style = "display: none";
      },
      
      onError: function(error) {
      	console.log(error);
      },
    })  
  	.render('#paypal-button-container');
